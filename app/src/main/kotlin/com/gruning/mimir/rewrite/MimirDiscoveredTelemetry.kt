package com.gruning.mimir.rewrite

import android.os.Process
import android.util.Log
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import java.io.File

/**
 * Telemetry read straight from the kernel, for the device the daemon cannot reach.
 *
 * **This exists because the standing rule leaves no other honest option.** Capability is DISCOVERED,
 * never DECLARED, on every device — and on the Thor the Tweaker daemon can
 * never bind, because `CONTROL_TWEAKER` is `prot=signature` across two different signing keys. Without
 * this object the performance overlay's reader has nothing to read there, and the card on Main said
 * TELEMETRY PAUSED on a handheld whose thermal zones are world-readable. That absence was honest and it
 * was also avoidable, which makes it the wrong kind.
 *
 * **What it can honestly supply, and what it cannot.** Verified from Mimir's own app context on the
 * Thor, 2026-08-31:
 *
 * | reading | source | on this device |
 * |---|---|---|
 * | CPU temperature | hottest `thermal_zone*` typed `cpu*` | 63.1 °C, readable |
 * | GPU temperature | hottest `thermal_zone*` typed `gpu*` | 50.3 °C, readable |
 * | GPU busy | validated KGSL `gpu_busy_percentage`, `gpu_load`, or `gpubusy` | readable when published |
 * | CPU clocks | `policyN/scaling_cur_freq` | readable |
 * | GPU clock | `kgsl-3d0/devfreq/cur_freq` | readable |
 * | CPU busy (whole device) | — | supplied by the active utilization reader |
 * | **frame rate** | — | supplied by the active game renderer reader |
 *
 * The last two rows are the point of this table. They come back null, the surface renders "—", and
 * nothing invents a number to fill the space. A discovered source that quietly guessed the two readings
 * it cannot take would be worse than the daemon it replaces, because the daemon at least failed loudly.
 *
 * The one-shot fallback intentionally leaves rates and interval-derived CPU utilization to the
 * active reader. It only publishes values it can validate from the current kernel snapshot.
 *
 * **Hottest zone, not a mean** — the same law the fan readout follows (policy 2026-08-22, and [performanceOverlayCpuTemperature]). A mean
 * over classified zones can sit ~30 °C below the hottest core; an Odin measured CPU0/CPU1
 * at 89.9 °C while the package mean read comfortable. A readout that smooths away the hot cluster
 * under-reports exactly the moment it exists for.
 *
 * **It opens no transport and holds no reservation**, so the ONE-READER rule is untouched: this is the
 * same unprivileged filesystem read `MimirTuner` already does, not a second claim on the daemon session
 * that starved the Odin's Tweaker surface on 2026-08-10.
 */
internal object MimirDiscoveredTelemetry {

    private const val ThermalRoot = "/sys/class/thermal"
    private const val CpuFreqRoot = "/sys/devices/system/cpu/cpufreq"

    /**
     * The zones are enumerated ONCE and their paths kept, because the enumeration is the expensive half.
     *
     * There are 95 zones on the Thor and their types never change while the kernel is up; re-reading 95
     * `type` files on every beat to find the same handful would be paying a directory walk for a fact
     * that was already settled at boot. The temperatures themselves are re-read every time.
     */
    private data class Zones(val cpu: List<File>, val gpu: List<File>)

    @Volatile
    private var zones: Zones? = null

    @Volatile
    private var gpuSourceInitialized = false

    @Volatile
    private var lastGpuSource: String? = null

    private fun logGpuSourceEdge(source: String?) {
        synchronized(this) {
            if (gpuSourceInitialized && source == lastGpuSource) return
            gpuSourceInitialized = true
            lastGpuSource = source
            Log.i(
                GPU_TELEMETRY_TAG,
                "source=${source ?: "unavailable"} uid=${Process.myUid()}",
            )
        }
    }

    /** See [DirectGpuBusyReader]: no read while a Tweaker can be reached, and quiet when nothing answers. */
    private val directGpuBusy = DirectGpuBusyReader(
        tweakerReachable = { TweakerBridge.pairState() == TweakerPairState.ELIGIBLE },
        readNode = ::read,
        nowMs = android.os.SystemClock::elapsedRealtime,
    )

    private fun zones(): Zones = zones ?: synchronized(this) {
        zones ?: run {
            val cpu = mutableListOf<File>()
            val gpu = mutableListOf<File>()
            File(ThermalRoot).listFiles().orEmpty()
                .filter { it.name.startsWith("thermal_zone") }
                .forEach { zone ->
                    val type = runCatching { File(zone, "type").readText().trim() }.getOrNull() ?: return@forEach
                    val temp = File(zone, "temp")
                    when {
                        type.startsWith("cpu") -> cpu += temp
                        type.startsWith("gpu") -> gpu += temp
                    }
                }
            Zones(cpu, gpu).also { zones = it }
        }
    }

    /** Millidegrees to whole degrees, dropping anything outside a range a handheld could survive. */
    private fun hottestC(files: List<File>): Int? = files
        .mapNotNull { runCatching { it.readText().trim().toIntOrNull() }.getOrNull() }
        .map { it / 1_000 }
        .filter { it in 1..150 }
        .maxOrNull()

    private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    /**
     * A reading, or null if this device publishes nothing at all.
     *
     * Null means *stay quiet* — the surface then says it has no telemetry, which is true. A reading with
     * every field null would say the same thing far less clearly, in six dashes.
     */
    fun read(): TweakerTelemetry? {
        val cpuTempC = hottestC(zones().cpu)
        val gpuTempC = hottestC(zones().gpu)
        val gpuReading = directGpuBusy.read()
        val gpuBusy = gpuReading.percent
        logGpuSourceEdge(gpuReading.source)
        val gpuClockHz = read("${DeviceGpuPaths.ROOT}/devfreq/cur_freq")?.toLongOrNull()?.takeIf { it > 0L }
        val cpuClocksHz = File(CpuFreqRoot).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("policy") }
            .mapNotNull { read("${it.absolutePath}/scaling_cur_freq")?.toLongOrNull() }
            .filter { it > 0L }
            .map { it * 1_000L }
        if (
            cpuTempC == null &&
            gpuTempC == null &&
            gpuBusy == null &&
            cpuClocksHz.isEmpty() &&
            gpuClockHz == null
        ) return null
        return TweakerTelemetry(
            sampledAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            foregroundPackageName = null,
            frameStatus = null,
            // Frame rate belongs to the active game renderer reader; this one-shot kernel fallback
            // has no interval and therefore leaves it unavailable.
            frameAverageFpsMilli = null,
            cpuTempC = cpuTempC,
            gpuTempC = gpuTempC,
            // Whole-device CPU busy needs a counter interval; the active utilization reader owns it.
            cpuBusyPercent = null,
            gpuBusyPercent = gpuBusy,
            // Battery draw comes from the Android BatteryManager the environment already reads, not
            // from here -- one source per quantity.
            batteryPowerMw = null,
            batteryStatus = null,
            cpuPackageTempC = cpuTempC,
            cpuPolicyClocksHz = cpuClocksHz,
            gpuClockHz = gpuClockHz,
        )
    }

    private const val GPU_TELEMETRY_TAG = "MimirGpuTelemetry"
}
