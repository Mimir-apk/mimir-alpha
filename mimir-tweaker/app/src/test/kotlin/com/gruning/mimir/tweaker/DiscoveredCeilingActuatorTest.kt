package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v10 discovered ceiling, rule by rule.
 *
 * Every number here is an Odin-3-shaped OPP table rather than a round invented one, because the
 * rules that matter (nearest-step snapping, the live-floor clamp, the power-level inversion) all
 * behave correctly on evenly spaced integers and wrongly on real tables.
 */
class DiscoveredCeilingActuatorTest {

    // ---------------------------------------------------------------------------------------
    // 1. Snap + clamp
    // ---------------------------------------------------------------------------------------

    @Test
    fun `snaps a request onto the nearest published step`() {
        assertEquals(1516800, DiscoveredCeilingPlan.nearestSupportedKhz(SILVER_OPPS, 1_500_000))
        assertEquals(1017600, DiscoveredCeilingPlan.nearestSupportedKhz(SILVER_OPPS, 1_100_000))
        assertEquals(2016000, DiscoveredCeilingPlan.nearestSupportedKhz(SILVER_OPPS, 99_999_999))
        assertEquals(307200, DiscoveredCeilingPlan.nearestSupportedKhz(SILVER_OPPS, 1))
    }

    @Test
    fun `refuses a non-positive request and an empty table`() {
        assertNull(DiscoveredCeilingPlan.nearestSupportedKhz(SILVER_OPPS, 0))
        assertNull(DiscoveredCeilingPlan.nearestSupportedKhz(SILVER_OPPS, -1))
        assertNull(DiscoveredCeilingPlan.nearestSupportedKhz(emptyList(), 1_000_000))
    }

    @Test
    fun `clamps up to the live floor and never down`() {
        assertEquals(1017600, DiscoveredCeilingPlan.clampUpToLiveFloor(307200, 1017600))
        assertEquals(1017600, DiscoveredCeilingPlan.clampUpToLiveFloor(1017600, 1017600))
        assertEquals(2016000, DiscoveredCeilingPlan.clampUpToLiveFloor(2016000, 1017600))
        // Floor unknown means no clamp, never a guessed floor.
        assertEquals(307200, DiscoveredCeilingPlan.clampUpToLiveFloor(307200, null))
    }

    @Test
    fun `apply snaps then clamps and reports both numbers`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.scalingMax["$SILVER_PATH/scaling_max_freq"] = 1017600
        val store = FakeStore()
        val outcome = actuator(device, store).apply(
            axis = TweakerDiscoveredCeilingContract.AXIS_CPU,
            policyId = 0,
            ceilingKhz = 400_000,
        )
        val applied = outcome as CeilingOutcome.Applied
        assertEquals(400_000, applied.requestedKhz)
        // 400000 snaps to 307200, which sits below the settled live floor, so it is clamped up.
        assertEquals(1017600, applied.admittedKhz)
        assertEquals(1017600, applied.inForceKhz)
        assertEquals(
            listOf(
                "echo 0:1017600 > ${HardwareProbe.CPU_QOS_MAX_PATH}",
                "echo 1:1017600 > ${HardwareProbe.CPU_QOS_MAX_PATH}",
                "echo 2:1017600 > ${HardwareProbe.CPU_QOS_MAX_PATH}",
            ),
            device.commands,
        )
        assertEquals("0:1017600 1:1017600 2:1017600", store.record?.cpuRequestedRow)
    }

    @Test
    fun `apply refuses a policy this device never published`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        val outcome = actuator(device, FakeStore())
            .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, policyId = 42, ceilingKhz = 1_000_000)
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_POLICY,
            (outcome as CeilingOutcome.Refused).code,
        )
        assertTrue(device.commands.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 2. Stock correction: a cap somebody else holds is not stock
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a CPU held below its cluster ceiling is recorded at the ceiling`() {
        val corrected = DiscoveredCeilingPlan.stockCpuRowWithoutHeldCaps(
            liveRow = "0:1017600 1:2016000 2:$UNLIMITED 7:3187200",
            ceilingForCpu = mapOf(0 to 2016000, 1 to 2016000, 2 to 2016000, 7 to 3187200),
        )
        // cpu0 was held below its ceiling and is recorded AT the ceiling; cpu1 already sits there;
        // cpu2 is unlimited (above any published step); cpu7 is at its own cluster's ceiling.
        assertEquals("0:2016000 1:2016000 2:$UNLIMITED 7:3187200", corrected)
    }

    @Test
    fun `a CPU whose ceiling is unknown is left exactly as read`() {
        val corrected = DiscoveredCeilingPlan.stockCpuRowWithoutHeldCaps(
            liveRow = "0:1017600 9:604800",
            ceilingForCpu = mapOf(0 to 2016000),
        )
        assertEquals("0:2016000 9:604800", corrected)
    }

    @Test
    fun `apply captures a corrected stock row before it writes anything`() {
        // cpu1 arrives already capped by somebody else; the record must not call that stock.
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to 1017600, 2 to UNLIMITED))
        val store = FakeStore()
        actuator(device, store).apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals("0:$UNLIMITED 1:2016000 2:$UNLIMITED", store.record?.cpuStockRow)
    }

    @Test
    fun `nothing is written when the record cannot be persisted`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore(
            refusal = DiscoveredAxisAdmissionResult.Refused("UNDISCOVERED_FREQUENCY", "cpu 0"),
        )
        val outcome = actuator(device, store).apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
            (outcome as CeilingOutcome.Refused).code,
        )
        assertTrue(outcome.detail.contains("UNDISCOVERED_FREQUENCY"))
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `an undelivered write leaves the store exactly as it was`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.deliver = false
        val store = FakeStore()
        val outcome = actuator(device, store).apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
            (outcome as CeilingOutcome.Refused).code,
        )
        assertNull("a refused apply must not leave a record behind", store.record)
    }

    // ---------------------------------------------------------------------------------------
    // 3. The ours-check on withdraw
    // ---------------------------------------------------------------------------------------

    @Test
    fun `withdraw restores our CPUs and leaves a moved CPU alone`() {
        val plan = DiscoveredCeilingPlan.cpuRestorePlan(
            live = mapOf(0 to 1017600, 1 to 2400000, 2 to 1017600),
            requested = mapOf(0 to 1017600, 1 to 1017600, 2 to 1017600),
            stock = mapOf(0 to 2016000, 1 to 2016000, 2 to 2016000),
        )
        assertEquals(mapOf(0 to 2016000, 2 to 2016000), plan.restore)
        assertEquals(listOf(1), plan.leftAlone)
    }

    @Test
    fun `withdraw writes only the CPUs still holding our value`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 2400000, 2 to 1017600))
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "0:2016000 1:2016000 2:2016000",
                cpuRequestedRow = "0:1017600 1:1017600 2:1017600",
            ),
        )
        val outcome = actuator(device, store).withdraw() as CeilingOutcome.Withdrawn
        assertEquals(2, outcome.restoredCpus)
        assertEquals(1, outcome.leftAloneCpus)
        assertEquals(
            listOf(
                "echo 0:2016000 > ${HardwareProbe.CPU_QOS_MAX_PATH}",
                "echo 2:2016000 > ${HardwareProbe.CPU_QOS_MAX_PATH}",
            ),
            device.commands,
        )
        assertEquals(2400000, device.qosRow[1])
        assertNull("nothing of ours is left in force, so the record goes", store.record)
    }

    @Test
    fun `an undelivered withdraw keeps the record`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        device.deliver = false
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "0:2016000",
                cpuRequestedRow = "0:1017600",
            ),
        )
        val outcome = actuator(device, store).withdraw()
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
            (outcome as CeilingOutcome.Refused).code,
        )
        assertNotNull("the way back is the only thing that survives", store.record)
    }

    @Test
    fun `withdraw with no record for this boot reports NOTHING_HELD`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        val store = FakeStore()
        assertEquals(CeilingOutcome.NothingHeld, actuator(device, store).withdraw())
        assertTrue(device.commands.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 4. GPU ordering: min before max on apply, max before min on withdraw
    // ---------------------------------------------------------------------------------------

    @Test
    fun `gpu power level inverts an ascending table`() {
        assertEquals(0, DiscoveredCeilingPlan.gpuPowerLevelForKhz(GPU_OPPS, 800_000))
        assertEquals(1, DiscoveredCeilingPlan.gpuPowerLevelForKhz(GPU_OPPS, 719_000))
        assertEquals(3, DiscoveredCeilingPlan.gpuPowerLevelForKhz(GPU_OPPS, 310_000))
        assertEquals(719_000, DiscoveredCeilingPlan.gpuKhzForPowerLevel(GPU_OPPS, 1))
    }

    @Test
    fun `gpu apply widens min before it writes max`() {
        assertEquals(
            listOf(
                "echo 3 > ${HardwareProbe.GPU_MIN_LEVEL}",
                "echo 1 > ${HardwareProbe.GPU_MAX_LEVEL}",
            ),
            DiscoveredCeilingPlan.gpuCeilingCommands(slowestLevel = 3, maxLevel = 1),
        )
    }

    @Test
    fun `gpu withdraw restores max before min`() {
        assertEquals(
            listOf(
                "echo 0 > ${HardwareProbe.GPU_MAX_LEVEL}",
                "echo 0 > ${HardwareProbe.GPU_MIN_LEVEL}",
            ),
            DiscoveredCeilingPlan.gpuRestoreCommands(stockMinLevel = 0, stockMaxLevel = 0),
        )
    }

    @Test
    fun `a gpu ceiling takes only because min was widened first`() {
        // FakeDevice models the kernel's own clamp: max_pwrlevel can never name a slower level than
        // min_pwrlevel. Writing max first against a stock floor of 0 would snap straight back to 0.
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        val store = FakeStore()
        val outcome = actuator(device, store).apply(
            axis = TweakerDiscoveredCeilingContract.AXIS_GPU,
            policyId = TweakerDiscoveredCeilingContract.GPU_POLICY_ID,
            ceilingKhz = 719_000,
        )
        val applied = outcome as CeilingOutcome.Applied
        assertEquals(719_000, applied.admittedKhz)
        assertEquals(719_000, applied.inForceKhz)
        assertEquals(3, device.gpuMin)
        assertEquals(1, device.gpuMax)
        assertEquals(0, store.record?.gpuStockMinLevel)
        assertEquals(0, store.record?.gpuStockMaxLevel)
        assertEquals(3, store.record?.gpuRequestedMinLevel)
        assertEquals(1, store.record?.gpuRequestedMaxLevel)
    }

    @Test
    fun `a gpu withdraw puts both bounds back`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 3, gpuMax = 1)
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "",
                cpuRequestedRow = "",
                gpuStockMinLevel = 0,
                gpuStockMaxLevel = 0,
                gpuRequestedMinLevel = 3,
                gpuRequestedMaxLevel = 1,
            ),
        )
        val outcome = actuator(device, store).withdraw() as CeilingOutcome.Withdrawn
        assertTrue(outcome.gpuRestored)
        assertEquals(0, device.gpuMin)
        assertEquals(0, device.gpuMax)
    }

    // S3 closing review: this used to read (min 3, max 2) as "bounds moved", but min 3 is exactly what we wrote, and a pair
    // is judged bound by bound now. A pair where NEITHER bound holds our value is somebody else's.
    @Test
    fun `a gpu whose bounds both moved is left alone`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 2, gpuMax = 2)
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "",
                cpuRequestedRow = "",
                gpuStockMinLevel = 0,
                gpuStockMaxLevel = 0,
                gpuRequestedMinLevel = 3,
                gpuRequestedMaxLevel = 1,
            ),
        )
        val outcome = actuator(device, store).withdraw() as CeilingOutcome.Withdrawn
        assertEquals(false, outcome.gpuRestored)
        assertTrue(device.commands.isEmpty())
        assertEquals(2, device.gpuMax)
    }

    @Test
    fun `a device with no gpu axis refuses the gpu axis`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        val outcome = actuator(device, FakeStore(), gpuAvailable = false).apply(
            TweakerDiscoveredCeilingContract.AXIS_GPU,
            TweakerDiscoveredCeilingContract.GPU_POLICY_ID,
            719_000,
        )
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_NO_GPU_AXIS,
            (outcome as CeilingOutcome.Refused).code,
        )
    }

    // ---------------------------------------------------------------------------------------
    // 5. Boot binding
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a record from another boot reads NOT_HELD and is cleared`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = "a-previous-boot",
                cpuStockRow = "0:2016000",
                cpuRequestedRow = "0:1017600",
            ),
        )
        assertEquals(CeilingOutcome.NotHeld, actuator(device, store).query())
        assertNull(store.record)
        assertTrue("a record from another boot is never repaired", device.commands.isEmpty())
    }

    @Test
    fun `a record from this boot whose value is in force reads HELD without writing`() {
        val record = DiscoveredAxisRecord(
            bootIdentity = BOOT,
            cpuStockRow = "0:2016000",
            cpuRequestedRow = "0:1017600",
        )
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        val outcome = actuator(device, FakeStore(record = record)).query() as CeilingOutcome.Held
        assertEquals(record, outcome.record)
        assertTrue(outcome.inForce)
        assertTrue("a cap that is in force needs no repair", device.commands.isEmpty())
    }

    @Test
    fun `an unreadable boot_id falls back to the frontend's bucketed elapsed identity`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED), bootId = null)
        val store = FakeStore()
        actuator(device, store, clock = FixedClock(wall = 1_700_000_123_456L, elapsed = 3_456L))
            .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals("elapsed:${(1_700_000_123_456L - 3_456L) / 10_000L}", store.record?.bootIdentity)
    }

    // ---------------------------------------------------------------------------------------
    // 6. Precedence: posture, then an active named profile
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a read-only build refuses before it looks at anything else`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        val outcome = actuator(device, FakeStore(), writesCompiled = false)
            .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_POSTURE_NOT_ADMITTED,
            (outcome as CeilingOutcome.Refused).code,
        )
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `every non-stock session state refuses the ceiling`() {
        listOf("APPLYING", "ACTIVE", "RESTORING", "RECOVERY_FAILED", "CORRUPT", "UNKNOWN").forEach { state ->
            val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
            val store = FakeStore()
            val outcome = actuator(device, store, sessionState = state)
                .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
            val refused = outcome as CeilingOutcome.Refused
            assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_PROFILE_ACTIVE, refused.code)
            assertTrue("the detail must name the state", refused.detail.contains(state))
            assertTrue(device.commands.isEmpty())
            assertNull(store.record)
        }
    }

    @Test
    fun `a profile apply withdraws a held ceiling first and reports it`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "0:2016000 1:2016000 2:2016000",
                cpuRequestedRow = "0:1017600 1:1017600 2:1017600",
            ),
        )
        val lines = mutableListOf<String>()
        // Deliberately with a live session: the precedence path must not consult session truth,
        // because a session is exactly what the apply it runs ahead of is about to create.
        val precedence = actuator(device, store, sessionState = "APPLYING", lines = lines)
            .withdrawBeforeProfileApply()
        assertEquals(CeilingPrecedence.Withdrawn, precedence)
        assertEquals(2016000, device.qosRow[0])
        assertNull(store.record)
        assertTrue(
            lines.any {
                it == "DISCOVERED_CEILING withdrawn before profile apply restored=3 leftAlone=0 gpu=false"
            },
        )
    }

    @Test
    fun `a profile apply is blocked when the ceiling cannot be withdrawn`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        device.deliver = false
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "0:2016000",
                cpuRequestedRow = "0:1017600",
            ),
        )
        val precedence = actuator(device, store).withdrawBeforeProfileApply()
        assertTrue(precedence is CeilingPrecedence.Blocked)
        assertNotNull(store.record)
    }

    @Test
    fun `a profile apply with no ceiling held proceeds untouched`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        assertEquals(
            CeilingPrecedence.NothingHeld,
            actuator(device, FakeStore()).withdrawBeforeProfileApply(),
        )
        assertTrue(device.commands.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 7. Observability: the device gates
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every operation emits exactly one line`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.scalingMax["$SILVER_PATH/scaling_max_freq"] = 1516800
        val store = FakeStore()
        val lines = mutableListOf<String>()
        val subject = actuator(device, store, lines = lines)

        subject.apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals(
            "DISCOVERED_CEILING apply axis=CPU policy=0 requested=1516800 admitted=1516800 " +
                "delivered=true inForce=1516800 outcome=APPLIED",
            lines.single { it.startsWith("DISCOVERED_CEILING apply") },
        )

        subject.query()
        assertEquals(
            "DISCOVERED_CEILING query outcome=HELD inForce=true",
            lines.single { it.startsWith("DISCOVERED_CEILING query") },
        )

        subject.withdraw()
        assertEquals(
            "DISCOVERED_CEILING withdraw restored=3 leftAlone=0 gpu=false outcome=WITHDRAWN",
            lines.single { it.startsWith("DISCOVERED_CEILING withdraw ") },
        )
    }

    @Test
    fun `a refusal line carries its code`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        val lines = mutableListOf<String>()
        actuator(device, FakeStore(), sessionState = "ACTIVE", lines = lines)
            .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        val line = lines.single { it.startsWith("DISCOVERED_CEILING apply") }
        assertTrue(line.contains("outcome=REFUSED"))
        assertTrue(line.contains("code=PROFILE_ACTIVE"))
    }

    // ---------------------------------------------------------------------------------------
    // 8. The keeper: a held ceiling is watched, because this node has a second writer
    //
    // Device finding (AYN Thor, 2026-09-11, daemon 0.4.1-tw04): a confirmed 7:1843200 held for
    // 40+ s and then read 7:2147483647 within seconds of the system relaunching the stopped HOME
    // app, with NO daemon line at all. The daemon wrote nothing; the vendor performance HAL did.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a tick re-asserts only the CPU that drifted, with our value`() {
        // cpu1 was reset to INT_MAX by the other writer; cpu0 and cpu2 still carry our cap.
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to UNLIMITED, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD)
        val lines = mutableListOf<String>()
        actuator(device, store, lines = lines).keeperTick()
        assertEquals(
            listOf("echo 1:1017600 > ${HardwareProbe.CPU_QOS_MAX_PATH}"),
            device.commands,
        )
        assertEquals(1017600, device.qosRow[1])
        assertEquals(
            "DISCOVERED_CEILING repair cpus=1 gpu=false reason=drift",
            lines.single { it.startsWith("DISCOVERED_CEILING repair") },
        )
        assertNotNull("a repair never touches the record", store.record)
    }

    @Test
    fun `a tick with every CPU still at our value writes nothing and says nothing`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val lines = mutableListOf<String>()
        actuator(device, FakeStore(record = CPU_RECORD), lines = lines).keeperTick()
        assertTrue(device.commands.isEmpty())
        assertTrue(lines.none { it.startsWith("DISCOVERED_CEILING repair") })
    }

    /**
     * S9 "Unreadable CPU repair evidence". A row that does not name every CPU of ours is not a reading of this node: it
     * shows no drift, so it triggers no re-assertion (this used to count the missing CPU as drifted and write to it).
     */
    @Test
    fun `a live row that does not name every CPU of ours is unreadable and re-asserts nothing`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 2 to 1017600))
        val lines = mutableListOf<String>()
        val store = FakeStore(record = CPU_RECORD)
        actuator(device, store, lines = lines).keeperTick()
        assertTrue("an incomplete row is not drift evidence: nothing is written", device.commands.isEmpty())
        assertNotNull(store.record)
        assertTrue(lines.any { it.startsWith("DISCOVERED_CEILING repair skipped reason=cpu_row_unreadable") })
    }

    @Test
    fun `a live row with a malformed pair is unreadable and re-asserts nothing, even for a CPU that read as drifted`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to 1017600, 2 to 1017600))
        device.rawQosRow = "0:${UNLIMITED} 1:1017600 2:1017600 garbage"
        actuator(device, FakeStore(record = CPU_RECORD)).keeperTick()
        assertTrue("the lenient parser would have dropped the pair and read cpu0 as drifted", device.commands.isEmpty())
    }

    @Test
    fun `the budget admits two repairs per burst, then defers, then rolls forward`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val clock = MovingClock()
        val lines = mutableListOf<String>()
        val store = FakeStore(record = CPU_RECORD)
        val subject = actuator(device, store, clock = clock, lines = lines)
        // Five drifts, one per second: the other writer resetting cpu1 as fast as it can.
        repeat(5) { second ->
            clock.elapsed = second * 1_000L
            device.qosRow[1] = UNLIMITED
            subject.keeperTick()
        }
        assertEquals(2, lines.count { it.startsWith("DISCOVERED_CEILING repair cpus=") })
        assertEquals(3, lines.count { it == "DISCOVERED_CEILING repair deferred reason=budget" })
        assertEquals("exhaustion does nothing; it never restores", 2, device.commands.size)
        assertNotNull("exhaustion never clears the record", store.record)

        // The window rolls forward on its own -- no reset(), no restore, just a later tick.
        clock.elapsed = 6_000L
        device.qosRow[1] = UNLIMITED
        subject.keeperTick()
        assertEquals(3, lines.count { it.startsWith("DISCOVERED_CEILING repair cpus=") })
    }

    @Test
    fun `the budget admits at most four repairs in the thirty second window`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val clock = MovingClock()
        val lines = mutableListOf<String>()
        val subject = actuator(device, FakeStore(record = CPU_RECORD), clock = clock, lines = lines)
        // Spaced past the burst window every time, so only the 30 s window can be what stops it.
        listOf(0L, 6_000L, 12_000L, 18_000L, 24_000L).forEach { at ->
            clock.elapsed = at
            device.qosRow[1] = UNLIMITED
            subject.keeperTick()
        }
        assertEquals(4, lines.count { it.startsWith("DISCOVERED_CEILING repair cpus=") })
        assertEquals(1, lines.count { it == "DISCOVERED_CEILING repair deferred reason=budget" })
    }

    @Test
    fun `a tick re-widens the GPU min before it re-writes max`() {
        // Both bounds opened back up past ours: the GPU's spelling of the row going to INT_MAX.
        val device = FakeDevice(qosRow = mutableMapOf(), gpuMin = 0, gpuMax = 0)
        val lines = mutableListOf<String>()
        actuator(device, FakeStore(record = GPU_RECORD), lines = lines).keeperTick()
        assertEquals(
            listOf(
                "echo 3 > ${HardwareProbe.GPU_MIN_LEVEL}",
                "echo 1 > ${HardwareProbe.GPU_MAX_LEVEL}",
            ),
            device.commands,
        )
        assertEquals(3, device.gpuMin)
        assertEquals(1, device.gpuMax)
        assertEquals(
            "DISCOVERED_CEILING repair cpus=none gpu=true reason=drift",
            lines.single { it.startsWith("DISCOVERED_CEILING repair") },
        )
    }

    @Test
    fun `a GPU with only one bound moved is left alone`() {
        // max is back at 0 but min is still ours: somebody owns exactly one bound, or is mid-write.
        val device = FakeDevice(qosRow = mutableMapOf(), gpuMin = 3, gpuMax = 0)
        actuator(device, FakeStore(record = GPU_RECORD)).keeperTick()
        assertTrue(device.commands.isEmpty())
        assertEquals(
            false,
            DiscoveredCeilingPlan.gpuBoundsOpened(
                liveMinLevel = 3,
                liveMaxLevel = 0,
                requestedMinLevel = 3,
                requestedMaxLevel = 1,
            ),
        )
        assertEquals(
            false,
            DiscoveredCeilingPlan.gpuBoundsOpened(
                liveMinLevel = null,
                liveMaxLevel = null,
                requestedMinLevel = 3,
                requestedMaxLevel = 1,
            ),
        )
    }

    @Test
    fun `an apply starts the keeper and a withdraw stops it`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val keeper = FakeKeeper()
        val subject = actuator(device, FakeStore(), keeper = keeper)
        subject.apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertTrue("a delivered cap must be watched", keeper.isRunning())
        subject.withdraw()
        assertEquals(false, keeper.isRunning())
    }

    @Test
    fun `a refused apply with nothing held never starts the keeper`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.deliver = false
        val keeper = FakeKeeper()
        actuator(device, FakeStore(), keeper = keeper)
            .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals(false, keeper.isRunning())
    }

    @Test
    fun `a withdraw that could not be delivered keeps the record AND its watcher`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        device.deliver = false
        val keeper = FakeKeeper()
        val store = FakeStore(
            record = DiscoveredAxisRecord(
                bootIdentity = BOOT,
                cpuStockRow = "0:2016000",
                cpuRequestedRow = "0:1017600",
            ),
        )
        actuator(device, store, keeper = keeper).withdraw()
        assertNotNull(store.record)
        assertTrue("nothing was given back, so the cap still needs watching", keeper.isRunning())
    }

    @Test
    fun `a withdraw stops the keeper before it reads the live row`() {
        // The ours-check decides ownership from the live row. If the keeper were still ticking, the
        // row it compares against would be one the keeper had just rewritten underneath it.
        val trace = mutableListOf<String>()
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600), trace = trace)
        val keeper = FakeKeeper(trace)
        actuator(device, FakeStore(record = CPU_RECORD), keeper = keeper).withdraw()
        val stoppedAt = trace.indexOf("keeper:stop")
        val firstRowRead = trace.indexOfFirst { it == "read:${HardwareProbe.CPU_QOS_MAX_PATH}" }
        assertTrue("the keeper must be stopped at all", stoppedAt >= 0)
        assertTrue("the live row must be read at all", firstRowRead >= 0)
        assertTrue("stop must come first, not merely also happen", stoppedAt < firstRowRead)
    }

    @Test
    fun `a tick whose record is gone stops the keeper instead of repairing`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val keeper = FakeKeeper()
        val store = FakeStore(record = CPU_RECORD)
        val subject = actuator(device, store, keeper = keeper)
        keeper.start {}
        store.record = null
        subject.keeperTick()
        assertEquals(false, keeper.isRunning())
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `a daemon start with a record for this boot starts the keeper`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val keeper = FakeKeeper()
        val lines = mutableListOf<String>()
        val subject = actuator(device, FakeStore(record = CPU_RECORD), keeper = keeper, lines = lines)
        subject.resumeIfHeld()
        assertTrue("a daemon restart must not orphan a held ceiling", keeper.isRunning())
        assertEquals(
            "DISCOVERED_CEILING keeper resumed cpus=0,1,2 gpu=false",
            lines.single { it.startsWith("DISCOVERED_CEILING keeper resumed") },
        )

        // The line belongs to the stopped -> running TRANSITION, not to the call. Thor logged it
        // three times inside one second (09:02:42.669/42.954/43.408, three tids) while the keeper
        // was already running: the behaviour was right, the line claimed two starts that never were.
        subject.resumeIfHeld()
        subject.resumeIfHeld()
        assertEquals(1, keeper.starts)
        assertEquals(
            "an idempotent re-sync stays silent",
            1,
            lines.count { it.startsWith("DISCOVERED_CEILING keeper resumed") },
        )

        // A real stop -> start still speaks.
        keeper.stop()
        subject.resumeIfHeld()
        assertEquals(2, keeper.starts)
        assertEquals(2, lines.count { it.startsWith("DISCOVERED_CEILING keeper resumed") })
    }

    @Test
    fun `a daemon start with nothing held, another boot's record or a read-only build starts nothing`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        FakeKeeper().let { keeper ->
            actuator(device, FakeStore(), keeper = keeper).resumeIfHeld()
            assertEquals(false, keeper.isRunning())
        }
        FakeKeeper().let { keeper ->
            val stale = FakeStore(record = CPU_RECORD.copy(bootIdentity = "a-previous-boot"))
            actuator(device, stale, keeper = keeper).resumeIfHeld()
            assertEquals(false, keeper.isRunning())
        }
        FakeKeeper().let { keeper ->
            actuator(device, FakeStore(record = CPU_RECORD), keeper = keeper, writesCompiled = false)
                .resumeIfHeld()
            assertEquals("a read-only build cannot re-assert anything", false, keeper.isRunning())
        }
    }

    @Test
    fun `the keeper and a named profile session can never both be live`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        // 1. An apply while a session exists is refused, so no keeper is ever started next to one.
        FakeKeeper().let { keeper ->
            actuator(device, FakeStore(), sessionState = "ACTIVE", keeper = keeper)
                .apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
            assertEquals(false, keeper.isRunning())
        }
        // 2. A daemon start while a session exists resumes nothing.
        FakeKeeper().let { keeper ->
            actuator(device, FakeStore(record = CPU_RECORD), sessionState = "APPLYING", keeper = keeper)
                .resumeIfHeld()
            assertEquals(false, keeper.isRunning())
        }
        // 3. The only door into a session runs the precedence withdraw first, and that stops it.
        FakeKeeper().let { keeper ->
            val live = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
            val subject = actuator(live, FakeStore(), keeper = keeper)
            subject.apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
            assertTrue(keeper.isRunning())
            assertEquals(CeilingPrecedence.Withdrawn, subject.withdrawBeforeProfileApply())
            assertEquals(false, keeper.isRunning())
        }
    }

    /**
     * S9 "V10 serialization and truth": a query never repairs hardware. It used to write the drifted cap back before
     * answering (a read that was a write, on a Binder thread, outside the transaction worker); that is the keeper's job.
     */
    @Test
    fun `a query whose values are gone writes nothing and answers HELD inForce false`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore(record = CPU_RECORD)
        val lines = mutableListOf<String>()
        val outcome = actuator(device, store, lines = lines).query() as CeilingOutcome.Held
        assertEquals(false, outcome.inForce)
        assertTrue("a query must never write hardware", device.commands.isEmpty())
        assertNotNull("a transient miss must never delete the way back", store.record)
        assertEquals(
            "DISCOVERED_CEILING query outcome=HELD inForce=false",
            lines.single { it.startsWith("DISCOVERED_CEILING query") },
        )
    }

    @Test
    fun `the keeper, not the query, puts a drifted cap back`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val subject = actuator(device, FakeStore(record = CPU_RECORD))
        assertEquals(false, (subject.query() as CeilingOutcome.Held).inForce)
        assertTrue(device.commands.isEmpty())
        subject.keeperTick()
        assertEquals(3, device.commands.size)
        assertEquals(true, (subject.query() as CeilingOutcome.Held).inForce)
    }

    @Test
    fun `a row that is not a complete reading is never claimed in force`() {
        // cpu0 reads as our value, but a malformed pair beside it means the row is not a reading of this node.
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to UNLIMITED, 2 to UNLIMITED))
        device.rawQosRow = "0:1017600 1:${UNLIMITED} 2:${UNLIMITED} garbage"
        val outcome = actuator(device, FakeStore(record = CPU_RECORD)).query() as CeilingOutcome.Held
        assertEquals("unreadable is never an in-force claim", false, outcome.inForce)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `a read-only build repairs nothing, on a tick or through a query`() {
        // query reaches the repair WITHOUT passing through apply's posture check, so the guard has
        // to be on the repair itself: a build that cannot write hardware must not write through a
        // read either.
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore(record = CPU_RECORD)
        val subject = actuator(device, store, writesCompiled = false)
        subject.keeperTick()
        val outcome = subject.query() as CeilingOutcome.Held
        assertTrue("a read-only build never delivers a command", device.commands.isEmpty())
        assertEquals("and never claims a cap it cannot verify", false, outcome.inForce)
        assertNotNull(store.record)
    }

    @Test
    fun `a query after the keeper's repair budget is exhausted still answers HELD without clearing anything`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val clock = MovingClock()
        val store = FakeStore(record = CPU_RECORD)
        val lines = mutableListOf<String>()
        val subject = actuator(device, store, clock = clock, lines = lines)
        // Spend the burst on ticks (the third is deferred by the budget), then ask: the query itself repairs nothing.
        repeat(3) {
            device.qosRow.keys.toList().forEach { cpu -> device.qosRow[cpu] = UNLIMITED }
            subject.keeperTick()
        }
        device.qosRow.keys.toList().forEach { cpu -> device.qosRow[cpu] = UNLIMITED }
        val outcome = subject.query() as CeilingOutcome.Held
        assertEquals(false, outcome.inForce)
        assertNotNull(store.record)
        assertTrue(lines.any { it == "DISCOVERED_CEILING repair deferred reason=budget" })
    }

    // ---------------------------------------------------------------------------------------
    // 5. A write that did not complete keeps its record unless it provably put nothing on the node
    // ---------------------------------------------------------------------------------------

    private fun cpuApply(subject: DiscoveredCeilingActuator) =
        subject.apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)

    private fun gpuApply(subject: DiscoveredCeilingActuator) = subject.apply(
        TweakerDiscoveredCeilingContract.AXIS_GPU,
        TweakerDiscoveredCeilingContract.GPU_POLICY_ID,
        719_000,
    )

    @Test
    fun `an AMBIGUOUS cpu write that lands keeps the intent record, refuses as unconfirmed and keeps watching`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        // The first command answers AMBIGUOUS (the call threw, or came back unparseable) but lands.
        device.scripted = { index -> if (index == 0) CheckedCommandStatus.AMBIGUOUS else null }
        val store = FakeStore()
        val keeper = FakeKeeper()

        val outcome = cpuApply(actuator(device, store, keeper = keeper))

        val refused = outcome as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE, refused.code)
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertEquals("0:1516800 1:1516800 2:1516800", store.record?.cpuRequestedRow)
        assertEquals("0:$UNLIMITED 1:$UNLIMITED 2:$UNLIMITED", store.record?.cpuStockRow)
        assertTrue("a possibly-applied ceiling still needs its watcher", keeper.isRunning())
        assertEquals(1516800, device.qosRow[0])
    }

    @Test
    fun `a late AMBIGUOUS write is restored from the kept record, so it is never left without a way back`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.scripted = { index -> if (index == 0) CheckedCommandStatus.AMBIGUOUS else null }
        val store = FakeStore()
        val subject = actuator(device, store)
        cpuApply(subject)
        assertNotNull(store.record)

        val outcome = subject.withdraw()

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Withdrawn)
        assertEquals("the ceiling that landed is put back to stock", UNLIMITED, device.qosRow[0])
        assertEquals(UNLIMITED, device.qosRow[1])
        assertEquals(UNLIMITED, device.qosRow[2])
        assertNull(store.record)
    }

    @Test
    fun `an AMBIGUOUS write over an already-held ceiling keeps the new intent, not the prior record`() {
        // policy 3 names cpus 3..6, so the stock row must cover all four: S9 refuses an apply whose CPU has no stock entry.
        val device = FakeDevice(
            qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600, 3 to UNLIMITED, 4 to UNLIMITED, 5 to UNLIMITED, 6 to UNLIMITED),
        )
        val prior = DiscoveredAxisRecord(
            bootIdentity = BOOT,
            cpuStockRow = "0:2016000 1:2016000 2:2016000 3:$UNLIMITED 4:$UNLIMITED 5:$UNLIMITED 6:$UNLIMITED",
            cpuRequestedRow = "0:1017600 1:1017600 2:1017600",
        )
        val store = FakeStore(record = prior)
        device.scripted = { index -> if (index == 0) CheckedCommandStatus.AMBIGUOUS else null }

        val outcome = actuator(device, store).apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 3, 1_478_400)

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Refused)
        val kept = store.record!!
        assertTrue("the new policy's CPUs are in the record, so they can be restored", kept.cpuRequestedRow.contains("3:1478400"))
        assertTrue(kept.cpuRequestedRow.contains("0:1017600"))
    }

    @Test
    fun `a port that throws may have sent the command, so its record is kept too`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.throwOn = { index -> index == 0 }
        val store = FakeStore()

        val outcome = cpuApply(actuator(device, store))

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertNotNull(store.record)
    }

    @Test
    fun `a failure after an earlier step landed keeps the record, because that step is in force`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.scripted = { index -> if (index == 1) CheckedCommandStatus.FAILED else null }
        device.scriptedLands = false
        val store = FakeStore()

        val outcome = cpuApply(actuator(device, store))

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Refused)
        assertEquals("step one landed", 1516800, device.qosRow[0])
        assertNotNull("so its record must survive", store.record)
    }

    @Test
    fun `only a FAILED first command rolls the record back, to the prior record when there was one`() {
        val prior = CPU_RECORD
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        device.scripted = { index -> if (index == 0) CheckedCommandStatus.FAILED else null }
        device.scriptedLands = false
        val store = FakeStore(record = prior)

        val outcome = actuator(device, store).apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, !refused.detail.startsWith("UNCONFIRMED"))
        assertEquals("nothing landed, so the store is exactly as it was", prior, store.record)
        assertEquals(1017600, device.qosRow[0])
    }

    @Test
    fun `an AMBIGUOUS gpu write keeps the intent record and a FAILED first gpu command does not`() {
        val ambiguous = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        ambiguous.scripted = { index -> if (index == 0) CheckedCommandStatus.AMBIGUOUS else null }
        val keptStore = FakeStore()
        val keeper = FakeKeeper()

        val unconfirmed = gpuApply(actuator(ambiguous, keptStore, keeper = keeper)) as CeilingOutcome.Refused

        assertTrue(unconfirmed.detail, unconfirmed.detail.startsWith("UNCONFIRMED"))
        assertEquals(3, keptStore.record?.gpuRequestedMinLevel)
        assertEquals(1, keptStore.record?.gpuRequestedMaxLevel)
        assertEquals(0, keptStore.record?.gpuStockMinLevel)
        assertTrue(keeper.isRunning())

        val failed = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        failed.scripted = { index -> if (index == 0) CheckedCommandStatus.FAILED else null }
        failed.scriptedLands = false
        val rolledBack = FakeStore()

        gpuApply(actuator(failed, rolledBack))

        assertNull("a first command that provably did not run leaves nothing to keep", rolledBack.record)
        assertEquals(0, failed.gpuMin)
    }

    @Test
    fun `a gpu write that widened min and then failed keeps the record`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        device.scripted = { index -> if (index == 1) CheckedCommandStatus.FAILED else null }
        device.scriptedLands = false
        val store = FakeStore()

        gpuApply(actuator(device, store))

        assertEquals("min_pwrlevel was widened", 3, device.gpuMin)
        assertNotNull(store.record)
    }

    @Test
    fun `an unreadable readback after a confirmed write keeps the record, because nothing proves it did not land`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore()
        // The stock row is readable for the capture; the readback after the write is not.
        var unreadableReads = 0
        val subject = DiscoveredCeilingActuator(
            discovery = object : CeilingDiscovery {
                override fun cpuPolicies(): List<CeilingCpuPolicy> = POLICIES
                override fun gpuFrequenciesKhz(): List<Int> = GPU_OPPS
                override fun gpuAxisAvailable(): Boolean = true
            },
            nodes = { path ->
                if (path == HardwareProbe.CPU_QOS_MAX_PATH && device.commands.isNotEmpty()) {
                    unreadableReads++
                    null
                } else {
                    device.read(path)
                }
            },
            commands = device::execute,
            records = store,
            clock = FixedClock(),
            session = { null },
            keeper = FakeKeeper(),
            hardwareWritesCompiled = true,
        )

        val outcome = cpuApply(subject) as CeilingOutcome.Refused

        assertTrue("the readback was attempted", unreadableReads > 0)
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD, outcome.code)
        assertTrue(outcome.detail, outcome.detail.startsWith("UNCONFIRMED"))
        assertNotNull(store.record)
    }

    @Test
    fun `a readback showing only part of our value keeps the record, and one showing none rolls it back`() {
        val partial = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val partialStore = FakeStore()
        val subject = DiscoveredCeilingActuator(
            discovery = object : CeilingDiscovery {
                override fun cpuPolicies(): List<CeilingCpuPolicy> = POLICIES
                override fun gpuFrequenciesKhz(): List<Int> = GPU_OPPS
                override fun gpuAxisAvailable(): Boolean = true
            },
            nodes = partial::read,
            commands = { command ->
                val result = partial.execute(command)
                // CPUs 1 and 2 swallow their writes; CPU 0's write takes.
                if (!command.startsWith("echo 0:")) {
                    val cpu = command.removePrefix("echo ").substringBefore(':').toInt()
                    partial.qosRow[cpu] = UNLIMITED
                }
                result
            },
            records = partialStore,
            clock = FixedClock(),
            session = { null },
            keeper = FakeKeeper(),
            hardwareWritesCompiled = true,
        )
        val partialOutcome = cpuApply(subject) as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD, partialOutcome.code)
        assertTrue(partialOutcome.detail, partialOutcome.detail.startsWith("UNCONFIRMED"))
        assertNotNull("one CPU holds our value, so the record is the only way back", partialStore.record)

        val none = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        none.ignoreWrites = true
        val noneStore = FakeStore()
        val noneOutcome = cpuApply(actuator(none, noneStore)) as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD, noneOutcome.code)
        assertNull("a readable row with none of our value proves nothing of ours is held", noneStore.record)
    }

    @Test
    fun `a withdraw whose live row cannot be read keeps the record instead of clearing it`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD)
        val keeper = FakeKeeper()
        device.unreadable = true

        val outcome = actuator(device, store, keeper = keeper).withdraw()

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertNotNull("the cap is still on the node, so its record stays", store.record)
        assertTrue(device.commands.isEmpty())
        assertTrue(keeper.isRunning())
    }

    @Test
    fun `a gpu withdraw whose bounds cannot be read keeps the record instead of clearing it`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 3, gpuMax = 1)
        val store = FakeStore(record = GPU_RECORD)
        device.unreadable = true

        val outcome = actuator(device, store).withdraw()

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Refused)
        assertNotNull(store.record)
        assertTrue(device.commands.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Restore persistence: a kept record is cleared ONLY on a confirmed restore
    // ---------------------------------------------------------------------------------------

    private fun gpuWithdrawn(commandsFor: (FakeDevice) -> Unit = {}): Pair<FakeDevice, FakeStore> {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 3, gpuMax = 1)
        commandsFor(device)
        return device to FakeStore(record = GPU_RECORD)
    }

    @Test
    fun `a cpu restore the node swallowed is not cleared, it is refused as unconfirmed with the record kept`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        device.ignoreWrites = true // every restore command is CONFIRMED and changes nothing
        val store = FakeStore(record = CPU_RECORD)
        val keeper = FakeKeeper()

        val outcome = actuator(device, store, keeper = keeper).withdraw()

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertEquals(3, device.commands.size)
        assertEquals("the cap is still on the node, so its record stays", CPU_RECORD, store.record)
        assertEquals(0, store.clears)
        assertTrue("a cap that is still held keeps its watcher", keeper.isRunning())
    }

    @Test
    fun `a cpu restore another writer re-asserted straight away is not cleared either`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD)
        val subject = DiscoveredCeilingActuator(
            discovery = discoveryOf(),
            nodes = device::read,
            commands = { command ->
                val result = device.execute(command)
                // The vendor HAL puts CPU 1 back to our cap the instant the restore lands.
                if (command.startsWith("echo 1:")) device.qosRow[1] = 1017600
                result
            },
            records = store,
            clock = FixedClock(),
            session = { null },
            keeper = FakeKeeper(),
            hardwareWritesCompiled = true,
        )

        val outcome = subject.withdraw()

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertTrue("the detail names the CPU that did not come back: ${refused.detail}", refused.detail.contains("1"))
        assertNotNull(store.record)
    }

    @Test
    fun `a restore whose readback cannot be read is not cleared`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD)
        val subject = DiscoveredCeilingActuator(
            discovery = discoveryOf(),
            nodes = { path ->
                // Readable for the withdraw's own look at the row; unreadable once the restore has been sent.
                if (path == HardwareProbe.CPU_QOS_MAX_PATH && device.commands.isNotEmpty()) null else device.read(path)
            },
            commands = device::execute,
            records = store,
            clock = FixedClock(),
            session = { null },
            keeper = FakeKeeper(),
            hardwareWritesCompiled = true,
        )

        val outcome = subject.withdraw()

        assertTrue((outcome as CeilingOutcome.Refused).detail.startsWith("UNCONFIRMED"))
        assertNotNull(store.record)
    }

    @Test
    fun `a gpu restore the node swallowed is refused as unconfirmed with the record kept`() {
        val (device, store) = gpuWithdrawn { it.ignoreWrites = true }

        val outcome = actuator(device, store).withdraw()

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertEquals(GPU_RECORD, store.record)
        assertEquals("the bounds are still our cap", 3, device.gpuMin)
    }

    @Test
    fun `a restore that did not hold blocks a profile apply, it is never reported as withdrawn`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        device.ignoreWrites = true
        val store = FakeStore(record = CPU_RECORD)
        val keeper = FakeKeeper()

        val precedence = actuator(device, store, keeper = keeper).withdrawBeforeProfileApply()

        assertTrue(precedence.toString(), precedence is CeilingPrecedence.Blocked)
        assertNotNull(store.record)
        assertTrue(keeper.isRunning())
    }

    // (b) a half-delivered gpu apply: min landed, max did not

    @Test
    fun `a gpu pair where only min holds our value restores min and clears the record`() {
        // Captured (0,0), requested (3,1): the first write (min) landed, the second (max) did not.
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 3, gpuMax = 0)
        val store = FakeStore(record = GPU_RECORD)

        val outcome = actuator(device, store).withdraw()

        val withdrawn = outcome as CeilingOutcome.Withdrawn
        assertTrue(withdrawn.gpuRestored)
        assertEquals(listOf("echo 0 > ${HardwareProbe.GPU_MIN_LEVEL}"), device.commands)
        assertEquals("min is back at stock", 0, device.gpuMin)
        assertEquals(0, device.gpuMax)
        assertNull(store.record)
    }

    @Test
    fun `a gpu pair where only max holds our value restores max and leaves a min that is not ours`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 1)
        val store = FakeStore(record = GPU_RECORD)

        val withdrawn = actuator(device, store).withdraw() as CeilingOutcome.Withdrawn

        assertTrue(withdrawn.gpuRestored)
        assertEquals(listOf("echo 0 > ${HardwareProbe.GPU_MAX_LEVEL}"), device.commands)
        assertEquals(0, device.gpuMax)
        assertNull(store.record)
    }

    @Test
    fun `an apply whose second gpu write failed is given back by the next withdraw`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        device.scripted = { index -> if (index == 1) CheckedCommandStatus.FAILED else null }
        device.scriptedLands = false
        val store = FakeStore()
        val subject = actuator(device, store)
        gpuApply(subject)
        assertEquals("min was widened and max was not", 3, device.gpuMin)
        assertEquals(0, device.gpuMax)
        assertNotNull(store.record)
        device.scripted = { null }

        val outcome = subject.withdraw()

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Withdrawn)
        assertEquals("min is no longer left widened", 0, device.gpuMin)
        assertNull(store.record)
    }

    // (c) a malformed or incomplete cpu row is unreadable, never "not ours"

    private fun withdrawAgainstRow(row: String): Triple<CeilingOutcome, FakeStore, FakeDevice> {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD)
        val subject = DiscoveredCeilingActuator(
            discovery = discoveryOf(),
            nodes = { path -> if (path == HardwareProbe.CPU_QOS_MAX_PATH) row else device.read(path) },
            commands = device::execute,
            records = store,
            clock = FixedClock(),
            session = { null },
            keeper = FakeKeeper(),
            hardwareWritesCompiled = true,
        )
        return Triple(subject.withdraw(), store, device)
    }

    @Test
    fun `a cpu row missing a cpu of ours is unreadable, so the record is kept and nothing is written`() {
        val (outcome, store, device) = withdrawAgainstRow("0:1017600 2:1017600")

        val refused = outcome as CeilingOutcome.Refused
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertNotNull("cpu 1 is not shown to have stopped holding the cap", store.record)
        assertEquals(0, store.clears)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `a cpu row with a malformed pair is unreadable, never leftAlone`() {
        val (outcome, store, device) = withdrawAgainstRow("0:1017600 1:oops 2:1017600")

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Refused)
        assertNotNull(store.record)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `a nonblank row none of whose pairs parse is unreadable too`() {
        val (outcome, store, _) = withdrawAgainstRow("garbage")

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Refused)
        assertNotNull(store.record)
    }

    @Test
    fun `the strict row parse drops nothing silently`() {
        assertEquals(mapOf(0 to 1, 1 to 2), DiscoveredCeilingPlan.parseCpuQosRowStrict(" 0:1 1:2 \n"))
        assertNull(DiscoveredCeilingPlan.parseCpuQosRowStrict(null))
        assertNull(DiscoveredCeilingPlan.parseCpuQosRowStrict("   "))
        assertNull(DiscoveredCeilingPlan.parseCpuQosRowStrict("0:1 1:"))
        assertNull(DiscoveredCeilingPlan.parseCpuQosRowStrict("0:1 x:2"))
        assertNull(DiscoveredCeilingPlan.parseCpuQosRowStrict("0:1 7"))
        // The lenient parse is what hid it: it returns the one good pair and loses the other.
        assertEquals(mapOf(0 to 1), DiscoveredCeilingPlan.parseCpuQosRow("0:1 1:"))
    }

    @Test
    fun `an apply whose cpu readback is incomplete or malformed keeps its record instead of rolling it back`() {
        listOf("0:1516800 2:1516800", "garbage", "0:1516800 1:oops 2:1516800").forEach { after ->
            val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
            val store = FakeStore()
            val subject = DiscoveredCeilingActuator(
                discovery = discoveryOf(),
                nodes = { path ->
                    if (path == HardwareProbe.CPU_QOS_MAX_PATH && device.commands.isNotEmpty()) after else device.read(path)
                },
                commands = device::execute,
                records = store,
                clock = FixedClock(),
                session = { null },
                keeper = FakeKeeper(),
                hardwareWritesCompiled = true,
            )

            val outcome = cpuApply(subject) as CeilingOutcome.Refused

            assertEquals(after, TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD, outcome.code)
            assertTrue("$after: ${outcome.detail}", outcome.detail.startsWith("UNCONFIRMED"))
            assertNotNull("$after: a row that cannot be read does not prove nothing landed", store.record)
        }
    }

    // (d) the gpu apply's mismatch path

    @Test
    fun `a gpu apply whose max did not take but whose min did keeps the record`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        val store = FakeStore()
        val subject = DiscoveredCeilingActuator(
            discovery = discoveryOf(),
            nodes = device::read,
            commands = { command ->
                // Both writes are CONFIRMED; the node swallows only max_pwrlevel.
                if (command.endsWith(HardwareProbe.GPU_MAX_LEVEL)) {
                    device.commands += command
                    CheckedCommandResult(CheckedCommandStatus.CONFIRMED, detail = "")
                } else {
                    device.execute(command)
                }
            },
            records = store,
            clock = FixedClock(),
            session = { null },
            keeper = FakeKeeper(),
            hardwareWritesCompiled = true,
        )

        val outcome = gpuApply(subject) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD, outcome.code)
        assertTrue(outcome.detail, outcome.detail.startsWith("UNCONFIRMED"))
        assertEquals("min_pwrlevel stayed widened", 3, device.gpuMin)
        assertNotNull("so the intent record is the only way back", store.record)

        // And the way back works: min is restored from the kept record.
        val withdrawn = subject.withdraw()
        assertTrue(withdrawn.toString(), withdrawn is CeilingOutcome.Withdrawn)
        assertEquals(0, device.gpuMin)
        assertNull(store.record)
    }

    @Test
    fun `a gpu apply the node ignored entirely is rolled back, because nothing of ours is on it`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED), gpuMin = 0, gpuMax = 0)
        device.ignoreWrites = true
        val store = FakeStore()

        val outcome = gpuApply(actuator(device, store)) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD, outcome.code)
        assertTrue(outcome.detail, !outcome.detail.startsWith("UNCONFIRMED"))
        assertNull(store.record)
    }

    // (e) a record that cannot be read is not "nothing held"

    @Test
    fun `a withdraw over an unreadable record is refused, nothing is cleared and nothing is written`() {
        listOf("decode failed: JSONException", "admission refused: UNDISCOVERED_CPU", "read failed: IOException").forEach { why ->
            val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
            val store = FakeStore(record = CPU_RECORD).also { it.unreadable = why }

            val outcome = actuator(device, store).withdraw()

            val refused = outcome as CeilingOutcome.Refused
            assertEquals(why, TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, refused.code)
            assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
            assertEquals("$why: an unreadable record must never be cleared", 0, store.clears)
            assertTrue(device.commands.isEmpty())
        }
    }

    @Test
    fun `a store that throws on read is unreadable too, never NothingHeld`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        val store = FakeStore(record = CPU_RECORD).also { it.throwOnRead = true }

        val outcome = actuator(device, store).withdraw()

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Refused)
        assertEquals(0, store.clears)
    }

    @Test
    fun `precedence over an unreadable record is Unreadable, not NothingHeld, and the keeper is left alone`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD)
        val keeper = FakeKeeper()
        val subject = actuator(device, store, keeper = keeper)
        subject.resumeIfHeld()
        assertTrue(keeper.isRunning())
        store.unreadable = "decode failed: JSONException"

        val precedence = subject.withdrawBeforeProfileApply()

        assertTrue(precedence.toString(), precedence is CeilingPrecedence.Unreadable)
        assertEquals(0, store.clears)
        assertTrue("a transient read failure must not stop a watcher that is holding a cap", keeper.isRunning())
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `an apply over an unreadable record is refused before it captures anything`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD).also { it.unreadable = "decode failed: JSONException" }

        val outcome = cpuApply(actuator(device, store)) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, outcome.code)
        assertTrue(device.commands.isEmpty())
        assertEquals("the capped row must never be recorded as stock", CPU_RECORD, store.record)
    }

    @Test
    fun `a query over an unreadable record refuses instead of answering NotHeld`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        val store = FakeStore(record = CPU_RECORD).also { it.unreadable = "read failed: IOException" }
        val keeper = FakeKeeper().also { it.start {} }

        val outcome = actuator(device, store, keeper = keeper).query()

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, (outcome as CeilingOutcome.Refused).code)
        assertTrue(keeper.isRunning())
    }

    @Test
    fun `a keeper tick over an unreadable record repairs nothing and keeps watching`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore(record = CPU_RECORD)
        val keeper = FakeKeeper()
        val subject = actuator(device, store, keeper = keeper)
        subject.resumeIfHeld()
        store.unreadable = "decode failed: JSONException"

        subject.keeperTick()

        assertTrue(device.commands.isEmpty())
        assertTrue(keeper.isRunning())
    }

    @Test
    fun `a record from another boot is still cleared and still reads NothingHeld`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED))
        val store = FakeStore(record = CPU_RECORD.copy(bootIdentity = "some-other-boot"))

        assertEquals(CeilingOutcome.NothingHeld, actuator(device, store).withdraw())
        assertNull(store.record)
    }

    @Test
    fun `the profile-apply gate refuses on an exception, an unreadable record and a blocked withdraw, and only those let it through`() {
        val ceilingHeld = TweakerDiscoveredCeilingContract.REFUSAL_CEILING_HELD
        val unconfirmed = TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED
        val logs = mutableListOf<String>()
        fun gate(evaluate: () -> CeilingPrecedence) = CeilingPrecedenceGate.refusalCode(evaluate) { logs += it }

        // S9 P3: a check that THREW, or a record that could not be read, establishes no ceiling at all. Saying
        // CEILING_HELD there claims a ceiling exists; CEILING_UNCONFIRMED says only that none could be ruled out.
        assertEquals(unconfirmed, gate { throw IllegalStateException("probe blew up") })
        assertEquals(unconfirmed, gate { throw java.io.IOException("store gone") })
        assertEquals(unconfirmed, gate { CeilingPrecedence.Unreadable("decode failed") })
        assertEquals(ceilingHeld, gate { CeilingPrecedence.Blocked("restore did not hold") })
        assertNull(gate { CeilingPrecedence.NothingHeld })
        assertNull(gate { CeilingPrecedence.Withdrawn })
        assertTrue(logs.any { it.contains("IllegalStateException") })
    }

    @Test
    fun `the profile-apply gate gives an interrupt back instead of swallowing it`() {
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            val code = CeilingPrecedenceGate.refusalCode(evaluate = { throw InterruptedException("stop") }, log = {})
            interrupted.set(code != null && Thread.currentThread().isInterrupted)
        }
        thread.start()
        thread.join(3_000)

        assertTrue(interrupted.get())
    }

    @Test
    fun `each way the gate answers UNCONFIRMED carries the same accurate sentence, and none claims nothing was changed`() {
        val expected = "Couldn't confirm the ceiling state; the profile was not applied."
        val unconfirmed = TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED
        val thrown = CeilingPrecedenceGate.refusalCode({ throw IllegalStateException("withdraw step threw after it changed hardware") }) {}
        val unreadable = CeilingPrecedenceGate.refusalCode({ CeilingPrecedence.Unreadable("decode failed") }) {}
        assertEquals(unconfirmed, thrown)
        assertEquals(unconfirmed, unreadable)
        assertEquals(expected, CeilingPrecedenceGate.refusalMessage(thrown!!))
        assertEquals(expected, CeilingPrecedenceGate.refusalMessage(unreadable!!))
        // A precedence withdraw may have restored hardware before it threw, so no refusal text may promise otherwise.
        listOf(thrown!!, unreadable!!, TweakerDiscoveredCeilingContract.REFUSAL_CEILING_HELD, SERVICE_BUSY_CODE).forEach { code ->
            val message = CeilingPrecedenceGate.refusalMessage(code)
            assertTrue(message, !message.contains("nothing was changed"))
        }
    }

    @Test
    fun `the gate's two refusal codes say different things and neither claims a ceiling it did not establish`() {
        val held = CeilingPrecedenceGate.refusalMessage(TweakerDiscoveredCeilingContract.REFUSAL_CEILING_HELD)
        val unconfirmed = CeilingPrecedenceGate.refusalMessage(TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED)
        assertTrue(held, held.contains("ceiling is held"))
        assertEquals("Couldn't confirm the ceiling state; the profile was not applied.", unconfirmed)
        assertTrue(unconfirmed, !unconfirmed.contains("is held"))
        assertTrue(
            TweakerDiscoveredCeilingContract.REFUSAL_CEILING_HELD != TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED,
        )
    }

    // ---------------------------------------------------------------------------------------
    // S9: ceiling operations fail closed. A record is kept until an authoritative readback proves the hardware is
    // restored AND the record is read back as deleted.
    // ---------------------------------------------------------------------------------------

    /** A held CPU ceiling whose cap is in force, ready to be withdrawn. */
    private fun heldCpuCeiling(store: FakeStore = FakeStore(record = CPU_RECORD)): Pair<FakeDevice, FakeStore> =
        FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600)) to store

    @Test
    fun `a withdraw whose record cannot be deleted is refused, not Withdrawn, and does not restart the keeper`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        val keeper = FakeKeeper()
        val subject = actuator(device, store, keeper = keeper)
        subject.resumeIfHeld()
        assertTrue(keeper.isRunning())

        val outcome = subject.withdraw()

        val refused = outcome as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, refused.code)
        assertTrue(refused.detail, refused.detail.startsWith("UNCONFIRMED"))
        assertEquals("the hardware itself was restored and read back", 2016000, device.qosRow[0])
        assertEquals("the keeper must stand down", false, keeper.isRunning())
        // The surviving record said HELD; the keeper tick that would have followed it must find nothing to re-assert.
        val writesAfterRestore = device.commands.size
        subject.keeperTick()
        assertEquals("a surviving record must not re-cap hardware that was just restored", writesAfterRestore, device.commands.size)
        assertEquals(2016000, device.qosRow[0])
        // The restoration data is KEPT beside a durable pending-delete marker; it is not emptied.
        assertEquals(CPU_RECORD.copy(pendingDelete = true), store.record)
    }

    @Test
    fun `a surviving record is not followed by the keeper after a daemon restart either`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        actuator(device, store).withdraw()

        // A new process: no in-memory state, only the file.
        val restartedKeeper = FakeKeeper()
        val restarted = actuator(device, store, keeper = restartedKeeper)
        restarted.resumeIfHeld()
        assertEquals("a record that asks for nothing is not watched", false, restartedKeeper.isRunning())
        restarted.keeperTick()
        assertEquals(2016000, device.qosRow[0])
    }

    // S9 round 2 (P1): a failed delete used to REPLACE the restoration data with an empty record, and after a same-boot
    // restart the in-memory flag was false, so apply overwrote the survivor and wrote without confirming its deletion.
    @Test
    fun `a failed delete keeps the restoration data and marks the record pending delete`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true

        assertTrue(actuator(device, store).withdraw() is CeilingOutcome.Refused)

        val kept = store.record!!
        assertEquals("the stock row survives", CPU_RECORD.cpuStockRow, kept.cpuStockRow)
        assertEquals("the requested row survives", CPU_RECORD.cpuRequestedRow, kept.cpuRequestedRow)
        assertTrue("and the durable marker is set", kept.pendingDelete)
    }

    @Test
    fun `after a daemon restart a pending-delete record is never re-capped by the keeper or the resume`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        actuator(device, store).withdraw()
        // The hardware reads back at stock; a vendor reset now moves cpu0 onto the very value the surviving record names.
        device.qosRow[0] = 1017600
        val writes = device.commands.size

        // A new process: no in-memory state, only the file.
        val restartedKeeper = FakeKeeper()
        val restarted = actuator(device, store, keeper = restartedKeeper)
        restarted.resumeIfHeld()
        restarted.keeperTick()
        restarted.query()
        restarted.keeperTick()

        assertEquals("the keeper never started", false, restartedKeeper.isRunning())
        assertEquals("nothing was written from a spent record", writes, device.commands.size)
        assertEquals(1017600, device.qosRow[0])
    }

    @Test
    fun `after a daemon restart apply over a pending-delete record refuses until the delete succeeds, capturing and writing nothing`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        actuator(device, store).withdraw()
        val survivor = store.record
        val writes = device.commands.size

        val restarted = actuator(device, store)
        val refused = cpuApply(restarted) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, refused.code)
        assertEquals("nothing was written", writes, device.commands.size)
        assertEquals("the survivor was not overwritten", survivor, store.record)

        store.clearFails = false
        assertTrue(cpuApply(restarted) is CeilingOutcome.Applied)
        assertEquals("the new ceiling's record is a live one, not the spent survivor", false, store.record?.pendingDelete)
    }

    @Test
    fun `after a daemon restart a query finishes the delete before it answers NotHeld`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        actuator(device, store).withdraw()

        val restarted = actuator(device, store)
        assertTrue("still undeletable: no NotHeld", restarted.query() is CeilingOutcome.Refused)
        assertTrue(store.record != null)

        store.clearFails = false
        assertEquals(CeilingOutcome.NotHeld, restarted.query())
        assertNull(store.record)
    }

    @Test
    fun `after a daemon restart a withdraw retries the delete and gives nothing back a second time`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        actuator(device, store).withdraw()
        device.qosRow[0] = 1017600 // another writer now owns a value that happens to equal the spent record's
        val writes = device.commands.size

        val restarted = actuator(device, store)
        assertTrue("still undeletable", restarted.withdraw() is CeilingOutcome.Refused)
        store.clearFails = false
        val outcome = restarted.withdraw()

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Withdrawn)
        assertNull(store.record)
        assertEquals("the spent record is not re-planned, so no restore is re-issued", writes, device.commands.size)
        assertEquals(1017600, device.qosRow[0])
    }

    @Test
    fun `when the marker write fails the in-process flag still stops the keeper and refuses apply, and the residual is that a restart cannot know`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        store.writeThrows = true
        val subject = actuator(device, store, keeper = FakeKeeper())
        assertTrue(subject.withdraw() is CeilingOutcome.Refused)
        assertEquals("the unmarked record is untouched", CPU_RECORD, store.record)

        assertTrue("this process still refuses", cpuApply(subject) is CeilingOutcome.Refused)
        assertTrue(device.commands.none { it.contains("1516800") })
    }

    @Test
    fun `a profile apply over a ceiling whose record cannot be deleted is refused as unconfirmed, not as a held ceiling`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        val subject = actuator(device, store)
        val code = CeilingPrecedenceGate.refusalCode({ subject.withdrawBeforeProfileApply() }) {}
        assertEquals(
            "the hardware was proven at stock: nothing is held, so this is not CEILING_HELD",
            TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED,
            code,
        )

        // And the worker-side recheck over the now spent record says the same, after a restart too.
        val restarted = actuator(device, store)
        assertTrue(restarted.recordedCeilingBeforeCapture() is CeilingPrecedence.Unreadable)
        store.clearFails = false
        assertEquals(CeilingPrecedence.Withdrawn, restarted.withdrawBeforeProfileApply())
        assertNull(store.record)
    }

    // S9 round 3: a pending-delete marker is read from DISK whichever boot wrote it. A spent record from an earlier boot
    // still owes its confirmed delete, so apply must not overwrite it, and query/withdraw must not call a failed delete success.
    private fun spentFromAnotherBoot(): Triple<FakeDevice, FakeStore, DiscoveredAxisRecord> {
        val spent = CPU_RECORD.copy(bootIdentity = "some-other-boot", pendingDelete = true)
        return Triple(FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED)), FakeStore(record = spent), spent)
    }

    @Test
    fun `apply over a pending-delete record from another boot refuses while its delete fails, writing and capturing nothing, and proceeds once it works`() {
        val (device, store, spent) = spentFromAnotherBoot()
        store.clearFails = true
        val subject = actuator(device, store)

        val refused = cpuApply(subject) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, refused.code)
        assertEquals("the survivor was not overwritten", spent, store.record)
        assertTrue("nothing was written to the node", device.commands.isEmpty())

        store.clearFails = false
        assertTrue(cpuApply(subject) is CeilingOutcome.Applied)
        assertEquals(BOOT, store.record?.bootIdentity)
        assertEquals(false, store.record?.pendingDelete)
    }

    @Test
    fun `a query and a withdraw over a pending-delete record from another boot are not NotHeld or Withdrawn while its delete fails`() {
        val (device, store, spent) = spentFromAnotherBoot()
        store.clearFails = true
        val subject = actuator(device, store)

        val queried = subject.query() as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, queried.code)
        val withdrawn = subject.withdraw() as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, withdrawn.code)
        assertEquals(spent, store.record)
        assertTrue(device.commands.isEmpty())

        store.clearFails = false
        assertEquals(CeilingOutcome.NotHeld, subject.query())
        assertNull(store.record)

        val (device2, store2, _) = spentFromAnotherBoot()
        store2.clearFails = true
        val second = actuator(device2, store2)
        assertTrue(second.withdraw() is CeilingOutcome.Refused)
        store2.clearFails = false
        assertEquals(CeilingOutcome.NothingHeld, second.withdraw())
        assertNull(store2.record)
    }

    @Test
    fun `a profile apply over a pending-delete record from another boot is unconfirmed until its delete works`() {
        val (device, store, _) = spentFromAnotherBoot()
        store.clearFails = true
        val subject = actuator(device, store)

        assertTrue(subject.recordedCeilingBeforeCapture() is CeilingPrecedence.Unreadable)
        assertTrue(subject.withdrawBeforeProfileApply() is CeilingPrecedence.Unreadable)
        assertTrue("the record is kept", store.record != null)
        assertTrue(device.commands.isEmpty())

        store.clearFails = false
        assertEquals(CeilingPrecedence.NothingHeld, subject.withdrawBeforeProfileApply())
        assertNull(store.record)
    }

    @Test
    fun `a stale record from another boot that is not pending delete keeps its earlier answers`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore(record = CPU_RECORD.copy(bootIdentity = "some-other-boot"))
        val subject = actuator(device, store)

        assertEquals(CeilingPrecedence.NothingHeld, subject.recordedCeilingBeforeCapture())
        assertEquals(CeilingPrecedence.NothingHeld, subject.withdrawBeforeProfileApply())
        assertTrue("apply is not held up by a stale cap that died with the reboot", cpuApply(subject) is CeilingOutcome.Applied)
    }

    // Commit verification: Android's AtomicFile can fail a commit without throwing, so an Admitted write proves nothing.
    @Test
    fun `a marker write that reports success but persists nothing is a marker failure, kept in process memory`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        store.writeSilentlyDrops = true
        val keeper = FakeKeeper()
        val subject = actuator(device, store, keeper = keeper)

        val refused = subject.withdraw() as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, refused.code)
        assertTrue("the detail must not claim a marker that is not on disk: ${refused.detail}", !refused.detail.contains("marked spent"))
        assertEquals("the unmarked record is untouched", CPU_RECORD, store.record)
        assertEquals(false, keeper.isRunning())
        assertTrue("this process still refuses a new ceiling", cpuApply(subject) is CeilingOutcome.Refused)
        assertTrue(device.commands.none { it.contains("1516800") })
    }

    @Test
    fun `a marker write that reads back without the marker is a marker failure`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        store.writeDropsMarker = true
        val subject = actuator(device, store)

        val refused = subject.withdraw() as CeilingOutcome.Refused

        assertTrue(refused.detail, !refused.detail.contains("marked spent"))
        assertTrue("this process still refuses a new ceiling", cpuApply(subject) is CeilingOutcome.Refused)
    }

    @Test
    fun `a marker write that reads back as written is reported as marked`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        val refused = actuator(device, store).withdraw() as CeilingOutcome.Refused

        assertTrue(refused.detail, refused.detail.contains("marked spent"))
        assertEquals(CPU_RECORD.copy(pendingDelete = true), store.record)
    }

    @Test
    fun `a withdraw retried once the store works finishes the delete and reports Withdrawn`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        val subject = actuator(device, store)
        assertTrue(subject.withdraw() is CeilingOutcome.Refused)

        store.clearFails = false
        val outcome = subject.withdraw()

        assertTrue(outcome.toString(), outcome is CeilingOutcome.Withdrawn)
        assertNull(store.record)
    }

    @Test
    fun `a delete that answers true while the record is still there is not a delete`() {
        val (device, store) = heldCpuCeiling()
        store.clearLies = true
        val outcome = actuator(device, store).withdraw()
        assertTrue("clear() said true but the store still reads the record: $outcome", outcome is CeilingOutcome.Refused)
    }

    @Test
    fun `a delete that throws is not a delete`() {
        val (device, store) = heldCpuCeiling()
        store.clearThrows = true
        assertTrue(actuator(device, store).withdraw() is CeilingOutcome.Refused)
    }

    @Test
    fun `when even the emptying write fails the keeper stays off in this process and apply refuses until the delete works`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        store.writeThrows = true
        val keeper = FakeKeeper()
        val subject = actuator(device, store, keeper = keeper)
        subject.resumeIfHeld()

        assertTrue(subject.withdraw() is CeilingOutcome.Refused)
        assertEquals(false, keeper.isRunning())
        val writes = device.commands.size
        subject.keeperTick()
        assertEquals("the record still asks for the cap, but its delete is owed: nothing is re-asserted", writes, device.commands.size)
        subject.resumeIfHeld()
        assertEquals(false, keeper.isRunning())

        val refusedApply = cpuApply(subject) as CeilingOutcome.Refused
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE, refusedApply.code)
        assertEquals("an apply must not capture stock over an unfinished delete", writes, device.commands.size)

        store.clearFails = false
        store.writeThrows = false
        assertTrue(cpuApply(subject) is CeilingOutcome.Applied)
    }

    @Test
    fun `a query over a spent record finishes the delete and answers NotHeld only once it is gone`() {
        val (device, store) = heldCpuCeiling()
        store.clearFails = true
        val subject = actuator(device, store)
        subject.withdraw()

        assertTrue("delete still failing: the query does not claim NotHeld", subject.query() is CeilingOutcome.Refused)
        store.clearFails = false
        assertEquals(CeilingOutcome.NotHeld, subject.query())
        assertNull(store.record)
    }

    @Test
    fun `apply refuses a requested CPU the stock row has no entry for, before anything is persisted or written`() {
        // policy 3 names cpus 3..6, and the live row stops at 5: cpu6 would have no way back.
        val device = FakeDevice(qosRow = (0..5).associateWith { UNLIMITED }.toMutableMap())
        val store = FakeStore()

        val outcome = actuator(device, store).apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 3, 2_380_800) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE, outcome.code)
        assertTrue(outcome.detail, outcome.detail.contains("cpu 6"))
        assertTrue(device.commands.isEmpty())
        assertNull("a record with an uncoverable CPU must never be written", store.record)
    }

    @Test
    fun `apply refuses to capture stock from a row with a malformed pair`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        device.rawQosRow = "0:${UNLIMITED} 1:${UNLIMITED} 2:${UNLIMITED} oops"
        val store = FakeStore()

        val outcome = cpuApply(actuator(device, store)) as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE, outcome.code)
        assertTrue(device.commands.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun `a record written without stock for a requested CPU cannot be withdrawn, and nothing is cleared or written`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val store = FakeStore(record = CPU_RECORD.copy(cpuStockRow = "0:2016000 1:2016000"))

        val outcome = actuator(device, store).withdraw() as CeilingOutcome.Refused

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE, outcome.code)
        assertTrue(outcome.detail, outcome.detail.contains("cpu 2"))
        assertEquals("cpu2 must not be skipped while the record clears", 0, store.clears)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `a GPU request without stock beside it cannot be withdrawn either`() {
        val device = FakeDevice(qosRow = mutableMapOf(), gpuMin = 3, gpuMax = 1)
        val store = FakeStore(record = GPU_RECORD.copy(gpuStockMaxLevel = DiscoveredAxisRecord.ABSENT_LEVEL))

        assertTrue(actuator(device, store).withdraw() is CeilingOutcome.Refused)
        assertEquals(0, store.clears)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `a keeper that finds the record unreadable every second logs the transition, not every tick`() {
        val (device, store) = heldCpuCeiling()
        val lines = mutableListOf<String>()
        val subject = actuator(device, store, lines = lines)
        store.unreadable = "decode failed: JSONException"

        repeat(59) { subject.keeperTick() }
        assertEquals("one line for the transition, none for the next 58 ticks", 1, lines.count { it.startsWith("DISCOVERED_CEILING keeper tick skipped") })
        repeat(2) { subject.keeperTick() }
        assertEquals("and then one a minute", 2, lines.count { it.startsWith("DISCOVERED_CEILING keeper tick skipped") })

        store.unreadable = null
        subject.keeperTick()
        assertEquals(1, lines.count { it.startsWith("DISCOVERED_CEILING keeper record readable again") })
        store.unreadable = "decode failed: JSONException"
        subject.keeperTick()
        assertEquals("a new fault is a new transition", 3, lines.count { it.startsWith("DISCOVERED_CEILING keeper tick skipped") })
    }

    // ---------------------------------------------------------------------------------------
    // S9 "V10 serialization and truth": the ceiling/profile check-use race. The precedence check ran, a ceiling apply
    // landed, and only then did the profile reach the worker; the profile captured the cap as stock. The apply's own
    // block now re-reads the record on the worker, before any capture.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a ceiling applied between the precedence check and the profile's capture refuses the profile`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val store = FakeStore()
        val subject = actuator(device, store)
        fun gate(evaluate: () -> CeilingPrecedence) = CeilingPrecedenceGate.refusalCode(evaluate) {}

        // 1. The profile's precedence check: nothing held yet.
        assertNull(gate { subject.withdrawBeforeProfileApply() })
        // 2. A v10 apply lands in the gap before the profile's block reaches the worker.
        assertTrue(cpuApply(subject) is CeilingOutcome.Applied)
        // 3. The profile's own block re-checks before it captures stock: it must not proceed.
        assertEquals(
            TweakerDiscoveredCeilingContract.REFUSAL_CEILING_HELD,
            gate { subject.recordedCeilingBeforeCapture() },
        )
        assertEquals("the recheck wrote nothing", 3, device.commands.size)
        assertNotNull("and left the ceiling's record alone", store.record)
    }

    @Test
    fun `a profile that reaches the worker first leaves the ceiling nothing to capture over`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to UNLIMITED, 1 to UNLIMITED, 2 to UNLIMITED))
        val subject = actuator(device, FakeStore(), sessionState = null)
        fun gate(evaluate: () -> CeilingPrecedence) = CeilingPrecedenceGate.refusalCode(evaluate) {}

        assertNull(gate { subject.withdrawBeforeProfileApply() })
        assertNull("nothing recorded: the profile may capture", gate { subject.recordedCeilingBeforeCapture() })
        // The profile's session is adopted inside that same block, so a ceiling apply behind it is refused.
        val behind = actuator(device, FakeStore(), sessionState = "APPLYING").apply(TweakerDiscoveredCeilingContract.AXIS_CPU, 0, 1_516_800)
        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_PROFILE_ACTIVE, (behind as CeilingOutcome.Refused).code)
    }

    @Test
    fun `the recheck over an unreadable record is unconfirmed, and over a record from another boot is nothing held`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600))
        val unreadable = FakeStore(record = CPU_RECORD).also { it.unreadable = "decode failed: JSONException" }
        val gate = { subject: DiscoveredCeilingActuator ->
            CeilingPrecedenceGate.refusalCode({ subject.recordedCeilingBeforeCapture() }) {}
        }

        assertEquals(TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED, gate(actuator(device, unreadable)))
        assertNull(gate(actuator(device, FakeStore(record = CPU_RECORD.copy(bootIdentity = "some-other-boot")))))
        assertNull(gate(actuator(device, FakeStore())))
    }

    @Test
    fun `the recheck is read-only and never touches the keeper`() {
        val device = FakeDevice(qosRow = mutableMapOf(0 to 1017600, 1 to 1017600, 2 to 1017600))
        val keeper = FakeKeeper()
        val subject = actuator(device, FakeStore(record = CPU_RECORD), keeper = keeper)
        subject.resumeIfHeld()
        val stops = keeper.stops

        subject.recordedCeilingBeforeCapture()

        assertTrue(device.commands.isEmpty())
        assertEquals(stops, keeper.stops)
        assertTrue(keeper.isRunning())
    }

    private fun discoveryOf() = object : CeilingDiscovery {
        override fun cpuPolicies(): List<CeilingCpuPolicy> = POLICIES
        override fun gpuFrequenciesKhz(): List<Int> = GPU_OPPS
        override fun gpuAxisAvailable(): Boolean = true
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun actuator(
        device: FakeDevice,
        store: FakeStore,
        writesCompiled: Boolean = true,
        sessionState: String? = null,
        gpuAvailable: Boolean = true,
        clock: CeilingClock = FixedClock(),
        keeper: CeilingKeeperScheduler = FakeKeeper(),
        lines: MutableList<String> = mutableListOf(),
    ) = DiscoveredCeilingActuator(
        discovery = object : CeilingDiscovery {
            override fun cpuPolicies(): List<CeilingCpuPolicy> = POLICIES
            override fun gpuFrequenciesKhz(): List<Int> = if (gpuAvailable) GPU_OPPS else emptyList()
            override fun gpuAxisAvailable(): Boolean = gpuAvailable
        },
        nodes = device::read,
        commands = device::execute,
        records = store,
        clock = clock,
        session = { sessionState },
        keeper = keeper,
        hardwareWritesCompiled = writesCompiled,
        log = { lines += it },
    )

    private class FixedClock(
        private val wall: Long = 0L,
        private val elapsed: Long = 0L,
    ) : CeilingClock {
        override fun wallClockMs(): Long = wall
        override fun elapsedRealtimeMs(): Long = elapsed
    }

    /** The budget's only input is elapsed realtime, so a settable one is the whole test rig. */
    private class MovingClock(var elapsed: Long = 0L) : CeilingClock {
        override fun wallClockMs(): Long = 0L
        override fun elapsedRealtimeMs(): Long = elapsed
    }

    /**
     * The keeper without a thread.
     *
     * Records lifecycle into the SAME [trace] the device records its reads into, which is what lets
     * the withdraw-ordering test assert that the keeper was stopped BEFORE the live row was read
     * rather than merely that both happened.
     */
    private class FakeKeeper(private val trace: MutableList<String> = mutableListOf()) : CeilingKeeperScheduler {
        var tick: (() -> Unit)? = null
        var starts = 0
        var stops = 0
        private var running = false

        override fun start(tick: () -> Unit) {
            this.tick = tick
            if (!running) {
                starts++
                trace += "keeper:start"
            }
            running = true
        }

        override fun stop() {
            if (running) stops++
            running = false
            trace += "keeper:stop"
        }

        override fun isRunning(): Boolean = running
    }

    private class FakeStore(
        var record: DiscoveredAxisRecord? = null,
        private val refusal: DiscoveredAxisAdmissionResult.Refused? = null,
    ) : CeilingRecordStore {
        /** The store holds a record it cannot read (I/O, decode or admission failed): neither absent nor present. */
        var unreadable: String? = null

        /** The store throws out of read() instead of answering. */
        var throwOnRead = false
        var clears = 0

        /** clear() answers false and the record stays (a read-only volume, a busy file). */
        var clearFails = false

        /** clear() answers true but the record is still there: a store that lies about its own delete. */
        var clearLies = false

        /** clear() throws. */
        var clearThrows = false

        /** write() throws (the emptying write that neutralises a record that could not be deleted). */
        var writeThrows = false

        /** write() answers Admitted but persists nothing: an AtomicFile commit that failed without throwing. */
        var writeSilentlyDrops = false

        /** write() answers Admitted but persists the record without its pending-delete marker. */
        var writeDropsMarker = false

        override fun read(): DiscoveredAxisRecordRead {
            if (throwOnRead) throw java.io.IOException("store read failed")
            unreadable?.let { return DiscoveredAxisRecordRead.Unreadable(it) }
            return record?.let { DiscoveredAxisRecordRead.Present(it) } ?: DiscoveredAxisRecordRead.Absent
        }

        override fun write(record: DiscoveredAxisRecord): DiscoveredAxisAdmissionResult {
            if (writeThrows) throw java.io.IOException("store write failed")
            refusal?.let { return it }
            if (writeSilentlyDrops) return DiscoveredAxisAdmissionResult.Admitted
            this.record = if (writeDropsMarker) record.copy(pendingDelete = false) else record
            return DiscoveredAxisAdmissionResult.Admitted
        }

        override fun clear(): Boolean {
            clears++
            if (clearThrows) throw java.io.IOException("store delete failed")
            if (clearFails) return false
            if (clearLies) return true
            record = null
            return true
        }
    }

    /**
     * A device that actually applies the writes it is given, including the Adreno's own clamp:
     * `max_pwrlevel` can never name a slower level than `min_pwrlevel`, and vice versa. Modelling
     * the clamp rather than only recording the command order is what makes the ordering tests
     * prove a mechanism instead of a spelling.
     */
    private class FakeDevice(
        val qosRow: MutableMap<Int, Int>,
        var gpuMin: Int? = null,
        var gpuMax: Int? = null,
        var bootId: String? = BOOT,
        var deliver: Boolean = true,
        val trace: MutableList<String> = mutableListOf(),
    ) {
        val commands = mutableListOf<String>()
        val scalingMax = mutableMapOf<String, Int>()

        /**
         * The status the Nth command (0-based, across every call) answers with instead of CONFIRMED, and whether it
         * still LANDS on the node. An AMBIGUOUS write that lands is exactly the late write an abandoned deadline left.
         */
        var scripted: (Int) -> CheckedCommandStatus? = { null }
        var scriptedLands = true

        /** The Nth command throws out of the port instead of answering. */
        var throwOn: (Int) -> Boolean = { false }

        /** Every sysfs node the ceiling touches reads as null (a read that timed out or failed); boot_id still reads. */
        var unreadable = false

        /** The node swallows every write: confirmed, but nothing changes. */
        var ignoreWrites = false

        /** When set, the QoS node reads back this exact text instead of the formatted [qosRow]. */
        var rawQosRow: String? = null

        fun read(path: String): String? = trace.let {
            it += "read:$path"
            readNode(path)
        }

        private fun readNode(path: String): String? = when {
            unreadable && path != HardwareProbe.BOOT_ID_PATH -> null
            path == HardwareProbe.CPU_QOS_MAX_PATH ->
                rawQosRow ?: qosRow.keys.sorted().joinToString(" ") { "$it:${qosRow.getValue(it)}" }
            path == HardwareProbe.GPU_MIN_LEVEL -> gpuMin?.toString()
            path == HardwareProbe.GPU_MAX_LEVEL -> gpuMax?.toString()
            path == HardwareProbe.BOOT_ID_PATH -> bootId
            else -> scalingMax[path]?.toString()
        }

        fun execute(command: String): CheckedCommandResult {
            val index = commands.size
            commands += command
            if (throwOn(index)) throw IllegalStateException("port failed on command ${index + 1}")
            if (!deliver) return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "no privileged host")
            val forced = scripted(index)
            if (forced == null || scriptedLands) land(command)
            return CheckedCommandResult(forced ?: CheckedCommandStatus.CONFIRMED, detail = if (forced == null) "" else "scripted")
        }

        private fun land(command: String) {
            if (ignoreWrites) return
            val value = command.removePrefix("echo ").substringBefore(" > ")
            when (command.substringAfter(" > ")) {
                HardwareProbe.CPU_QOS_MAX_PATH ->
                    qosRow[value.substringBefore(':').toInt()] = value.substringAfter(':').toInt()
                HardwareProbe.GPU_MIN_LEVEL -> gpuMin = maxOf(value.toInt(), gpuMax ?: 0)
                HardwareProbe.GPU_MAX_LEVEL -> gpuMax = minOf(value.toInt(), gpuMin ?: 0)
            }
        }
    }

    private companion object {
        const val BOOT = "8f14e45f-ea8f-4bd2-9b14-1a4d2c3e5f60"
        const val UNLIMITED = Int.MAX_VALUE

        /** A held CPU ceiling on policy0's three cores, as apply leaves the record. */
        val CPU_RECORD = DiscoveredAxisRecord(
            bootIdentity = BOOT,
            cpuStockRow = "0:2016000 1:2016000 2:2016000",
            cpuRequestedRow = "0:1017600 1:1017600 2:1017600",
        )

        /** A held GPU ceiling: level 1 with the floor widened to the slowest level, as apply leaves it. */
        val GPU_RECORD = DiscoveredAxisRecord(
            bootIdentity = BOOT,
            cpuStockRow = "",
            cpuRequestedRow = "",
            gpuStockMinLevel = 0,
            gpuStockMaxLevel = 0,
            gpuRequestedMinLevel = 3,
            gpuRequestedMaxLevel = 1,
        )
        const val SILVER_PATH = "/sys/devices/system/cpu/cpufreq/policy0"
        val SILVER_OPPS = listOf(307200, 1017600, 1516800, 2016000)
        val GPU_OPPS = listOf(310_000, 500_000, 719_000, 800_000)
        val POLICIES = listOf(
            CeilingCpuPolicy(
                id = 0,
                path = SILVER_PATH,
                cpuIds = listOf(0, 1, 2),
                availableKhz = SILVER_OPPS,
                settledFloorKhz = 1017600,
            ),
            CeilingCpuPolicy(
                id = 3,
                path = "/sys/devices/system/cpu/cpufreq/policy3",
                cpuIds = listOf(3, 4, 5, 6),
                availableKhz = listOf(633600, 1478400, 2380800, 2707200),
                settledFloorKhz = null,
            ),
        )
    }
}
