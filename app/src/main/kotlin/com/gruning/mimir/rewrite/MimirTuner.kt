package com.gruning.mimir.rewrite

import java.io.File

/**
 * What Mimir can tune on THIS device, discovered from the kernel rather than declared in a manifest.
 *
 * Support is discovered from published SoC operating-point tables and the available privileged host,
 * independently of a per-model manifest. The Tweaker model asks
 * *"is this device on the list"* — a question whose answer is wrong by construction for every handheld
 * nobody has written a manifest for yet, and which on the Thor produced a permanent UNAVAILABLE
 * for a reason that had nothing to do with the hardware (see [PServerHost]). This asks *"what does this
 * SoC publish"*, which is answerable on any device, by anyone, at runtime, and cannot go stale.
 *
 * **Discovery needs no privilege at all.** Verified on the Thor, 2026-08-31, from Mimir's own app
 * context: `/sys/devices/system/cpu/cpufreq/policy{0,3,7}` and the Adreno's devfreq node are readable
 * without PServer, without root and without the daemon. So the READING half of every quality-of-service
 * control works on any device that exposes the tables, and only the WRITING half needs a privileged
 * host. That split is worth having explicitly: it means a device with no PServer still gets an honest,
 * populated panel that says what it cannot change, instead of an empty one that says UNAVAILABLE.
 */
data class MimirTunerPolicy(
    /** `policy0`, `policy3`, … for CPU; [GpuPolicyId] for the graphics core. */
    val id: Int,
    val kind: Kind,
    /** Human label for a surface: "CPU 0", "PRIME", "GPU". Assigned by the caller, never parsed. */
    val label: String,
    val nodePath: String,
    /**
     * Every frequency this policy will accept, **ascending, in kHz, always**.
     *
     * One unit and one direction for both kinds, because the kernel does not agree with itself: cpufreq
     * publishes ascending kHz and the Adreno devfreq node publishes DESCENDING Hz. Normalising at the
     * boundary means nothing downstream has to remember which node it is looking at — a distinction
     * that exists only in the filesystem and has no business reaching a picker.
     */
    val availableKhz: List<Int>,
    /** The cap in force right now, in kHz, or null when the node could not be read. */
    val currentMaxKhz: Int?,
) {
    enum class Kind { CPU, GPU }

    val ceilingKhz: Int? get() = availableKhz.lastOrNull()
    val floorKhz: Int? get() = availableKhz.firstOrNull()

    /** True when the cap in force is below what the silicon offers — i.e. something is holding it down. */
    val capped: Boolean
        get() {
            val current = currentMaxKhz ?: return false
            val ceiling = ceilingKhz ?: return false
            return current < ceiling
        }
}

/** The GPU is not a cpufreq policy, so it gets an id no policy directory can collide with. */
const val GpuPolicyId = -1

/**
 * What this device will let Mimir do, as two independent facts.
 *
 * **These are deliberately not collapsed into one "supported" boolean.** A boolean would have to pick
 * a side on a device that can be read but not written, and picking either side is a lie: saying
 * unsupported hides real readings, saying supported offers controls that cannot act. The panel renders
 * the pair.
 */
data class MimirTunerAvailability(
    /** A privileged host is present, so a cap can actually be written. */
    val canWrite: Boolean,
    /** What the kernel publishes. Empty means this SoC exposes no frequency tables at all. */
    val policies: List<MimirTunerPolicy>,
) {
    val canRead: Boolean get() = policies.isNotEmpty()
}

/** Where the kernel keeps the tables. Injectable so the parsing is testable off-device. */
interface MimirTunerNodes {
    fun listCpuPolicyDirectories(): List<String>
    fun read(path: String): String?
}

private const val CpuFreqRoot = "/sys/devices/system/cpu/cpufreq"
private const val GpuRoot = "/sys/class/kgsl/kgsl-3d0"

class RealMimirTunerNodes : MimirTunerNodes {
    override fun listCpuPolicyDirectories(): List<String> =
        File(CpuFreqRoot).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.map { it.absolutePath }
            .orEmpty()

    override fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}

/**
 * Ascending kHz from a cpufreq `scaling_available_frequencies` row.
 *
 * Sorted rather than trusted: the node is conventionally ascending and the contract downstream is that
 * it IS ascending, and a convention that has never been checked is a convention that eventually is not
 * true on somebody's device.
 */
internal fun parseCpuFrequenciesKhz(raw: String?): List<Int> =
    raw.orEmpty().trim().split(Regex("\\s+"))
        .mapNotNull { it.toIntOrNull() }
        .filter { it > 0 }
        .distinct()
        .sorted()

/**
 * Ascending kHz from the Adreno devfreq `available_frequencies` row, which publishes DESCENDING Hz.
 *
 * Two conversions in one place — order and unit — because both are properties of that one node and
 * neither should be a thing a caller has to know.
 */
internal fun parseGpuFrequenciesKhz(raw: String?): List<Int> =
    raw.orEmpty().trim().split(Regex("\\s+"))
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0L }
        .map { (it / 1_000L).toInt() }
        .filter { it > 0 }
        .distinct()
        .sorted()

/**
 * The label a cluster gets, from the shape of the silicon rather than from a device name.
 *
 * Three clusters is the common big.LITTLE.PRIME arrangement and reads that way; anything else is named
 * by its policy id rather than guessed at. A device with four clusters is not going to be told it has a
 * "prime" one because this function felt confident.
 */
internal fun cpuClusterLabel(policyId: Int, policyIds: List<Int>): String {
    val ordered = policyIds.sorted()
    if (ordered.size == 3) {
        return when (ordered.indexOf(policyId)) {
            0 -> "EFFICIENCY"
            1 -> "PERFORMANCE"
            2 -> "PRIME"
            else -> "CPU $policyId"
        }
    }
    return "CPU $policyId"
}

/**
 * Read every tunable policy this device publishes.
 *
 * A policy with no frequency table is DROPPED rather than reported empty: a cluster we cannot enumerate
 * is one we could not offer a choice for anyway, and listing it would put a dead row on a panel.
 */
fun discoverTunerPolicies(nodes: MimirTunerNodes = MimirTunerBackend.nodes()): List<MimirTunerPolicy> {
    val cpuDirectories = nodes.listCpuPolicyDirectories()
    val cpuIds = cpuDirectories.mapNotNull { it.substringAfterLast('/').removePrefix("policy").toIntOrNull() }
    val cpu = cpuDirectories.mapNotNull { directory ->
        val id = directory.substringAfterLast('/').removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
        val available = parseCpuFrequenciesKhz(nodes.read("$directory/scaling_available_frequencies"))
        if (available.isEmpty()) return@mapNotNull null
        MimirTunerPolicy(
            id = id,
            kind = MimirTunerPolicy.Kind.CPU,
            label = cpuClusterLabel(id, cpuIds),
            nodePath = directory,
            availableKhz = available,
            currentMaxKhz = nodes.read("$directory/scaling_max_freq")?.trim()?.toIntOrNull(),
        )
    }.sortedBy { it.id }

    val gpuAvailable = parseGpuFrequenciesKhz(nodes.read("$GpuRoot/devfreq/available_frequencies"))
    val gpu = if (gpuAvailable.isEmpty()) {
        null
    } else {
        MimirTunerPolicy(
            id = GpuPolicyId,
            kind = MimirTunerPolicy.Kind.GPU,
            label = "GPU",
            nodePath = GpuRoot,
            availableKhz = gpuAvailable,
            // The Adreno reports its ceiling as a power-level INDEX, counted from the fastest level.
            // Translating it back to kHz here keeps one vocabulary on the surface; see
            // gpuPowerLevelForKhz for the inverse, which is the half that has to be exactly right.
            currentMaxKhz = nodes.read("$GpuRoot/max_pwrlevel")?.trim()?.toIntOrNull()
                ?.let { level -> gpuKhzForPowerLevel(gpuAvailable, level) },
        )
    }

    return cpu + listOfNotNull(gpu)
}

/**
 * Adreno power level (0 = fastest) for a kHz target, against an ASCENDING table.
 *
 * The inversion is the whole function and it is the easiest thing in this file to get backwards: level
 * 0 is the LAST entry of an ascending table, not the first. An off-by-one-direction here would cap a
 * player's GPU at its floor while the surface claimed it had raised the ceiling.
 */
internal fun gpuPowerLevelForKhz(availableKhz: List<Int>, targetKhz: Int): Int {
    if (availableKhz.isEmpty()) return 0
    val nearest = availableKhz.indices.minByOrNull { kotlin.math.abs(availableKhz[it] - targetKhz) }
        ?: availableKhz.lastIndex
    return (availableKhz.lastIndex - nearest).coerceIn(0, availableKhz.lastIndex)
}

/** The inverse of [gpuPowerLevelForKhz], for rendering a level the kernel reports back to us. */
internal fun gpuKhzForPowerLevel(availableKhz: List<Int>, level: Int): Int? {
    if (availableKhz.isEmpty()) return null
    val index = availableKhz.lastIndex - level
    return availableKhz.getOrNull(index)
}

/**
 * Read this device's tuning surface: what it publishes, and whether anything can be written.
 *
 * Availability is computed here and nowhere else, so there is exactly one answer to "is this supported"
 * and it is always the current one.
 */
fun readTunerAvailability(nodes: MimirTunerNodes = MimirTunerBackend.nodes()): MimirTunerAvailability =
    MimirTunerAvailability(
        canWrite = MimirTunerBackend.hostAvailable(),
        policies = discoverTunerPolicies(nodes),
    )
