package com.gruning.mimir.tweaker

import android.provider.Settings
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The full-fan hold until cooling completes, against the real [PServerHardwareCommandPort]
 * and a fake chassis that behaves as the Odin did on 2026-10-14 03:19-12:06: the vendor's
 * Smart loop pulls duty back to its curve (23500/50000) within seconds of every force, and, as the
 * Nova measured on 2026-08-25, entering MANUAL makes the platform write half the period once.
 * Robolectric's `Settings.System` is `fan_mode`; the PWM nodes are a map the fake pServer writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PostControlHoldDeviceTest {

    private val period = 50_000
    private val curve = 23_500

    private inner class Chassis(fan: Int, duty: Int = curve) {
        val app = RuntimeEnvironment.getApplication()
        private val resolver = app.contentResolver
        var clock = 10_000L
        val commands = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val state = mutableMapOf(
            HardwareProbe.FAN_DUTY to duty.toString(),
            HardwareProbe.FAN_PERIOD to period.toString(),
            HardwareProbe.FAN_SPEED to "2000",
        )

        init {
            Settings.System.putString(resolver, "fan_mode", fan.toString())
        }

        fun fan(): Int? = Settings.System.getString(resolver, "fan_mode")?.toIntOrNull()
        fun duty(): Int = state.getValue(HardwareProbe.FAN_DUTY).toInt()

        /** Time passes: the vendor's Smart loop regulates only while the fan is Smart. */
        fun sleep(ms: Long) {
            sleeps += ms
            clock += ms
            if (fan() == TuningPlanner.FAN_SMART) state[HardwareProbe.FAN_DUTY] = curve.toString()
        }

        val pServer = object : PServerClient() {
            override fun executeChecked(command: String, nonce: String): CheckedCommandResult {
                commands += command
                command.split("&&").map(String::trim).forEach { step ->
                    Regex("^settings put system fan_mode (\\d+)$").matchEntire(step)?.let {
                        Settings.System.putString(resolver, "fan_mode", it.groupValues[1])
                        // The platform's one-shot half-period write on entering MANUAL (Nova, 2026-08-25).
                        if (it.groupValues[1].toInt() == TuningPlanner.FAN_MANUAL) {
                            state[HardwareProbe.FAN_DUTY] = (period / 2).toString()
                        }
                    }
                    Regex("^echo (\\d+) ?> ?(\\S+)$").matchEntire(step)
                        ?.let { state[it.groupValues[2]] = it.groupValues[1] }
                }
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
            }
        }

        val reader = SystemReader(
            privilegedNodes = null,
            directRead = { path -> if (path == HardwareProbe.BOOT_ID_PATH) "boot-1" else state[path] },
            directExists = { path -> path in state },
            directDirectories = { _, _ -> emptyList() },
        )

        val probe = HardwareProbe(
            context = app,
            reader = reader,
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ -> PServerTransactResult(accepted = false, output = null) },
            ),
            sleeper = {},
            pServerAvailable = { true },
        )

        val caps = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            // The Thor identity resolves the same AYN gpio5_pwm2 fan topology the Odin carries; the
            // vendor behaviour modelled above is the Odin's.
            manufacturer = "AYN",
            model = "Thor",
            socModel = "SM8550",
            buildFingerprint = "thor/test",
            deviceProfileId = HandheldDeviceProfiles.THOR_PROFILE_ID,
            supportedAxes = setOf(TuningAxis.FAN),
            pServerAvailable = true,
            cpuPolicies = emptyList(),
            gpuFrequenciesKhz = emptyList(),
            gpuNodeAvailable = false,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
            notes = emptyList(),
        )

        val port = PServerHardwareCommandPort(
            pServer = pServer,
            reader = reader,
            capabilities = { caps },
            fanModeRaw = probe::fanModeRaw,
            sleeper = ::sleep,
            elapsedRealtimeMs = { clock },
            sysfsResolver = identity,
        )

        val markerFile = File(Files.createTempDirectory("pch-device").toFile(), PostControlHoldMarker.FILE_NAME)
        val lines = mutableListOf<String>()
        val stamps = mutableListOf<Long>()

        fun watch() = PostControlThermalWatch(clock = PostControlClock { clock })

        fun controller(watch: PostControlThermalWatch) = PostControlHoldController(
            watch = watch,
            marker = PostControlHoldMarker(markerFile),
            live = port::postControlHoldLive,
            force = port::forceCoolingSmartReassert,
            enter = port::enterPostControlHold,
            correct = port::correctPostControlHold,
            release = port::releasePostControlHold,
            onFanWrite = { stamps += clock },
            now = { clock },
            log = { lines += it },
        )

        fun sample(cpu: Int, gpu: Int = 60) = PostControlThermalSample(
            cpuSafetyTempC = cpu,
            gpuTempC = gpu,
            vendorFanMode = fan(),
            duty = duty(),
            period = state.getValue(HardwareProbe.FAN_PERIOD).toInt(),
        )

        /** One watch tick a second, as the service schedules it. */
        fun tick(controller: PostControlHoldController, cpu: Int): PostControlThermalEvaluation {
            sleep(1_000L)
            return controller.tick(sample(cpu))
        }

        fun fanModePuts(mode: Int) = commands.count { "settings put system fan_mode $mode" in it }
    }

    private val identity = SysfsCanonicalResolver { path -> path }

    @Test
    fun `the Odin case -- one force, the vendor pulls duty back, exactly one hold, nothing more until cool, then one release`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_SMART)
        val watch = chassis.watch()
        val controller = chassis.controller(watch)

        // Tick 1: the force lands (full duty under Smart) and the vendor's loop pulls it back inside the settle.
        val first = chassis.tick(controller, cpu = 103)
        assertTrue("${first.decision}", first.decision is PostControlThermalDecision.ForceCooling)
        assertEquals(TuningPlanner.FAN_SMART, chassis.fan())
        assertEquals("pulled back to the curve, as on the Odin", curve, chassis.duty())
        assertEquals(1, chassis.fanModePuts(TuningPlanner.FAN_SMART))

        // Tick 2: the hold -- mode 6, the platform's half-period one-shot, then the full period, settled.
        val second = chassis.tick(controller, cpu = 103)
        assertTrue("${second.decision}", second.decision is PostControlThermalDecision.EnterHold)
        assertTrue(watch.isHolding())
        assertEquals(TuningPlanner.FAN_MANUAL, chassis.fan())
        assertEquals(period, chassis.duty())
        assertTrue(chassis.markerFile.isFile)
        assertEquals(
            "POST_CONTROL_HOLD enter reason=POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE mode=6 duty=50000 period=50000",
            chassis.lines.single(),
        )
        val writesAtEntry = chassis.commands.size

        // Twenty minutes hot: nothing is written. No pulsing.
        repeat(20 * 60) {
            val e = chassis.tick(controller, cpu = 101)
            assertEquals(PostControlThermalDecision.Holding, e.decision)
            assertFalse(e.shouldDisarm)
        }
        assertEquals(writesAtEntry, chassis.commands.size)
        assertEquals(period, chassis.duty())
        assertEquals(TuningPlanner.FAN_MANUAL, chassis.fan())
        assertEquals(1, chassis.fanModePuts(TuningPlanner.FAN_MANUAL))
        assertEquals("one heartbeat a minute while holding", 1 + 20, chassis.lines.size)

        // Cool below the hysteresis for the debounce: one release, the vendor regulates again.
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS - 1) {
            assertEquals(PostControlThermalDecision.Holding, chassis.tick(controller, cpu = 70).decision)
        }
        val release = chassis.tick(controller, cpu = 70)
        assertEquals(PostControlThermalDecision.ReleaseHold("cool"), release.decision)
        assertFalse(watch.isHolding())
        assertFalse(chassis.markerFile.exists())
        assertEquals(TuningPlanner.FAN_SMART, chassis.fan())
        assertEquals("the vendor's curve again", curve, chassis.duty())
        assertEquals(writesAtEntry + 1, chassis.commands.size)
        assertEquals("echo $period > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4", chassis.commands.last())
        assertTrue(chassis.lines.last(), chassis.lines.last().startsWith("POST_CONTROL_HOLD exit reason=cool mode=4 duty=23500 period=50000 heldMs="))

        // Then the ordinary disarm, Smart and cool.
        var disarmed = false
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS) { disarmed = chassis.tick(controller, cpu = 70).shouldDisarm }
        assertTrue(disarmed)
        assertEquals(3, chassis.stamps.size)
    }

    @Test
    fun `a session apply during the hold takes the fan back through the same checked hand-to-Smart`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_SMART)
        val watch = chassis.watch()
        val controller = chassis.controller(watch)
        chassis.tick(controller, cpu = 103)
        chassis.tick(controller, cpu = 103)
        assertTrue(watch.isHolding())

        assertEquals(PostControlHoldYield.RELEASED, controller.yieldToSession())

        assertFalse(watch.isHolding())
        assertFalse(chassis.markerFile.exists())
        assertEquals(TuningPlanner.FAN_SMART, chassis.fan())
        assertEquals("echo $period > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4", chassis.commands.last())
        assertTrue(chassis.lines.last(), chassis.lines.last().startsWith("POST_CONTROL_HOLD exit reason=session mode=4"))
        // The session's own manual entry would now find Smart, exactly as before this change.
        assertEquals(PostControlHoldYield.NOT_HELD, controller.yieldToSession())
    }

    @Test
    fun `a daemon restart over a stale hold releases it first, and holds again only through a fresh force if still hot`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_SMART)
        val dying = chassis.controller(chassis.watch())
        chassis.tick(dying, cpu = 103)
        chassis.tick(dying, cpu = 103)
        assertEquals(TuningPlanner.FAN_MANUAL, chassis.fan())
        assertTrue(chassis.markerFile.isFile)
        chassis.commands.clear()
        chassis.lines.clear()

        // The process died; a new one starts over the same marker with the fan still held.
        val watch = chassis.watch()
        val restarted = chassis.controller(watch)
        val first = chassis.tick(restarted, cpu = 103)
        assertTrue(chassis.lines.first(), chassis.lines.first().startsWith("POST_CONTROL_HOLD exit reason=stale_owner mode=4 duty=23500"))
        assertFalse(chassis.markerFile.exists())
        assertEquals("echo $period > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4", chassis.commands.first())
        // The tick's sample predates the release (it still reads MANUAL): nothing is decided on it.
        assertEquals(PostControlThermalDecision.Idle, first.decision)
        assertFalse(watch.isHolding())
        // Still hot under Smart on the next tick: the ordinary first step is a force, never a hold.
        assertTrue(chassis.tick(restarted, cpu = 103).decision is PostControlThermalDecision.ForceCooling)
        assertTrue(chassis.tick(restarted, cpu = 103).decision is PostControlThermalDecision.EnterHold)
        assertTrue(watch.isHolding())
        assertEquals(TuningPlanner.FAN_MANUAL, chassis.fan())
        assertEquals(period, chassis.duty())
    }

    @Test
    fun `a daemon restart over a stale hold on a cool device releases it and the watch disarms`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_MANUAL, duty = period)
        PostControlHoldMarker(chassis.markerFile).write(0L)
        val watch = chassis.watch()
        val controller = chassis.controller(watch)
        var disarmed = false
        // One tick releases (its own sample still reads MANUAL), then the ordinary debounce on Smart and cool.
        repeat(PostControlThermalWatch.DISARM_DEBOUNCE_TICKS + 1) { disarmed = chassis.tick(controller, cpu = 60).shouldDisarm }
        assertTrue(disarmed)
        assertEquals(TuningPlanner.FAN_SMART, chassis.fan())
        assertFalse(chassis.markerFile.exists())
        assertEquals(1, chassis.commands.size)
    }

    @Test
    fun `the entry refuses from anything but Smart and the release refuses anything but MANUAL -- fail closed, no write`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_SPORT)
        val refusedEntry = chassis.port.enterPostControlHold()
        assertEquals(CheckedCommandStatus.FAILED, refusedEntry.status)
        assertEquals("Post-control hold refused: live fan mode is 5, not vendor Smart", refusedEntry.detail)
        val refusedRelease = chassis.port.releasePostControlHold()
        assertEquals(CheckedCommandStatus.FAILED, refusedRelease.status)
        assertEquals("Post-control hold release refused: live fan mode is 5, not MANUAL", refusedRelease.detail)
        val refusedCorrection = chassis.port.correctPostControlHold()
        assertEquals(CheckedCommandStatus.FAILED, refusedCorrection.status)
        assertTrue(chassis.commands.isEmpty())
    }

    @Test
    fun `an entry whose duty does not settle is handed back to Smart inside the same call`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_SMART)
        // A chassis that keeps writing its own duty under MANUAL: every sleep drags it to half.
        val fighting = object : PServerClient() {
            override fun executeChecked(command: String, nonce: String): CheckedCommandResult =
                chassis.pServer.executeChecked(command, nonce)
        }
        val port = PServerHardwareCommandPort(
            pServer = fighting,
            reader = chassis.reader,
            capabilities = { chassis.caps },
            fanModeRaw = chassis.probe::fanModeRaw,
            sleeper = { ms ->
                chassis.sleep(ms)
                if (chassis.fan() == TuningPlanner.FAN_MANUAL) chassis.state[HardwareProbe.FAN_DUTY] = (period / 2).toString()
            },
            elapsedRealtimeMs = { chassis.clock },
            sysfsResolver = identity,
        )
        val result = port.enterPostControlHold()
        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertTrue(result.detail, result.detail.startsWith("Post-control hold lost at settle 1: mode=6 duty=25000 period=50000; handed back to Smart: CONFIRMED"))
        assertEquals(TuningPlanner.FAN_SMART, chassis.fan())
        assertEquals(
            listOf(
                "settings put system fan_mode 6",
                "echo $period>${HardwareProbe.FAN_DUTY}",
                "echo $period > ${HardwareProbe.FAN_DUTY} && settings put system fan_mode 4",
            ),
            chassis.commands,
        )
    }

    @Test
    fun `a held duty that drifts is corrected under MANUAL and the write shape never touches the mode`() {
        val chassis = Chassis(fan = TuningPlanner.FAN_SMART)
        val watch = chassis.watch()
        val controller = chassis.controller(watch)
        chassis.tick(controller, cpu = 103)
        chassis.tick(controller, cpu = 103)
        assertTrue(watch.isHolding())
        chassis.commands.clear()
        chassis.sleep(5_000L)
        chassis.state[HardwareProbe.FAN_DUTY] = (period / 2).toString()
        val corrected = chassis.tick(controller, cpu = 103)
        assertTrue("${corrected.decision}", corrected.decision is PostControlThermalDecision.CorrectHold)
        assertEquals(listOf("echo $period>${HardwareProbe.FAN_DUTY}"), chassis.commands)
        assertEquals(period, chassis.duty())
        assertEquals(TuningPlanner.FAN_MANUAL, chassis.fan())
        assertTrue(chassis.lines.last(), chassis.lines.last().startsWith("POST_CONTROL_HOLD correct duty=25000->50000 period=50000 mode=6 result=CONFIRMED"))
    }
}
