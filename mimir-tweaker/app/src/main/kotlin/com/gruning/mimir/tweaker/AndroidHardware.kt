package com.gruning.mimir.tweaker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.util.Log
import java.io.File

internal class SystemReader(
    private val privilegedNodes: PServerProtectedNodeAccess? = null,
    private val directRead: (String) -> String? = { path ->
        runCatching {
            File(path).readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    },
    private val directDirectories: (String, String) -> List<File> = { path, prefix ->
        runCatching {
            File(path).listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith(prefix) }
                .sortedBy { it.name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
        }.getOrDefault(emptyList())
    },
    /**
     * [HandheldDeviceProfiles.resolve] gained a
     * live [HandheldDeviceProfiles.fanPresent] gate on every device profile, not just RP5 -- see
     * that class's own KDoc. Production call sites of [exists] were already correct: a real device
     * genuinely does or does not have the sysfs nodes. What had no seam was a HOST JVM TEST double
     * -- every other query on this class (`directRead`, `directDirectories`) already had an
     * injectable default; `exists` alone hit the real filesystem unconditionally, which is exactly
     * why the asymmetry the same ruling retired went undetected by every test built on a
     * `SystemReader` whose `directRead` was stubbed but whose `exists` was not. Same pattern as its
     * two siblings above: a defaulted seam, byte-identical production behavior.
     */
    private val directExists: (String) -> Boolean = { path -> File(path).exists() },
) {
    fun read(path: String): String? = directRead(path) ?: privilegedNodes?.read(path)

    /** The unprivileged read alone. A node this app's SELinux domain may not open is audited on every call. */
    fun readDirect(path: String): String? = directRead(path)

    /** The helper's allowlisted `cat` alone; null for any path outside [PServerProtectedNodeAccess]'s allowlist. */
    fun readPrivileged(path: String): String? = privilegedNodes?.read(path)

    /** A privileged helper exists at all (structurally), as distinct from having just answered a read. */
    fun helperPresent(): Boolean = privilegedNodes?.available() == true

    fun directories(path: String, prefix: String): List<File> {
        val direct = directDirectories(path, prefix)
        return direct.ifEmpty {
            privilegedNodes?.directories(path, prefix).orEmpty()
        }
    }

    fun exists(path: String): Boolean = directExists(path)
}

/**
 * Bounded read-only fallback for protected cpufreq discovery. Commands accept only the
 * fixed cpufreq root and a strict policy-file allowlist; this is not a mutation backend.
 */
/**
 * Privileged reads of nodes this app cannot open directly, through the root helper.
 *
 * Renamed from `PServerProtectedNodeAccess` on 2026-08-25: it now also serves the GPU devfreq policy
 * ceiling, and a name that says "CpuTopology" while returning GPU state is the kind of quiet lie
 * this tree keeps finding at the bottom of its defects.
 */
internal class PServerProtectedNodeAccess(
    private val pServer: PServerClient,
) {
    fun read(path: String): String? {
        // An ALLOWLIST, widened by exactly one path and never by a pattern.
        //
        // The GPU devfreq policy ceiling is `rw-rw---- root:root`: the daemon can WRITE it through
        // this helper but could not READ it at all, so capturing its stock value failed and every
        // apply refused with CAPTURE_INCOMPLETE -- correctly, since it will not mutate what it
        // cannot restore. Reading a node we already write is the smaller privilege of the two.
        //
        // The three GPU utilization sources are the second widening, and each is exact, not a pattern: they
        // are vendor_sysfs_kgsl nodes this app's domain is denied (`avc: denied { read } ...
        // gpu_busy_percentage`, Nova 2026-10-01 09:41), they are read-only, and [GpuUtilizationReader]
        // range-checks every value before it becomes a number. `cat` follows the class symlink, so no
        // canonical spelling is needed: nothing here is captured or restored by path.
        if (
            !CPU_POLICY_FILE.matches(path) &&
            path != HardwareProbe.CPU_QOS_MAX_PATH &&
            path != HardwareProbe.GPU_DEVFREQ_MAX_FREQ &&
            path !in GpuUtilizationTelemetry.SOURCES &&
            path != devfreqMaxCanonical
        ) {
            return null
        }
        return pServer.execute("cat $path", captureOutput = true)
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * The devfreq ceiling's RESOLVED path, because the capture reads through
     * `SysfsPath.canonicalize` and `/sys/class/kgsl/...` is a symlink into the platform device tree
     * (`/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/...`). Allowlisting only the class
     * path let selection succeed and the capture then fail, which is how this arrived on the reference device
     * as CAPTURE_INCOMPLETE twice.
     *
     * Still an EXACT allowlist -- both spellings of one node, resolved once, never a pattern over
     * the device tree.
     */
    private val devfreqMaxCanonical: String? by lazy {
        SysfsPath.canonicalize(HardwareProbe.GPU_DEVFREQ_MAX_FREQ)
    }

    /** The helper's Binder is reachable at all. Not a read: it asks whether there is anyone to ask. */
    fun available(): Boolean = pServer.isAvailable

    fun directories(path: String, prefix: String): List<File> {
        if (path != CPU_ROOT || prefix != POLICY_PREFIX) return emptyList()
        val raw = pServer.execute(LIST_POLICIES_COMMAND, captureOutput = true).getOrNull()
        return raw.orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(POLICY_DIRECTORY::matches)
            .distinct()
            .map(::File)
            .sortedBy { it.name.removePrefix(POLICY_PREFIX).toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
    }

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu/cpufreq"
        const val POLICY_PREFIX = "policy"
        const val LIST_POLICIES_COMMAND =
            "find $CPU_ROOT -maxdepth 1 -type d -name 'policy[0-9]*' -print"
        val POLICY_DIRECTORY = Regex("^$CPU_ROOT/policy[0-9]{1,3}$")
        val CPU_POLICY_FILE = Regex(
            "^$CPU_ROOT/policy[0-9]{1,3}/" +
                "(?:affected_cpus|related_cpus|scaling_available_frequencies|" +
                "cpuinfo_min_freq|cpuinfo_max_freq|scaling_max_freq|scaling_min_freq|scaling_cur_freq)$",
        )
    }
}

/** One read-only live cpufreq window; neither field is transaction-owned state. */
internal data class CpuLiveWindowSample(
    val minKhz: Int?,
    val maxKhz: Int?,
)

/**
 * @property admittedTargetsKhz Targets as ACTUALLY admitted, after raising any target that sat
 * below its policy's live floor up to that floor. Empty when nothing was clamped. Policy
 * 2026-08-23 — see [CpuWindowAdmissionPolicy.targetAdmission].
 */
internal data class CpuWindowAdmission(
    val values: Map<Int, CpuLiveWindowSample> = emptyMap(),
    val failures: List<String> = emptyList(),
    val code: String? = null,
    val admittedTargetsKhz: Map<Int, Int> = emptyMap(),
)

/**
 * A discoverable fan controller is not automatically a safe transaction axis. Admission
 * requires both the fixed PWM topology and the vendor Settings bridge; the planner and
 * transaction validator separately restrict manual passthrough to the exact qualified Odin
 * profile. Restore ordering remains explicit because a vendor mode transition can replay
 * compute policy.
 *
 * [HandheldDeviceProfiles.supportsFanMode] widened from a three-id
 * allow-list to the whole RP/AYN family, and [HandheldDeviceProfile.fanNodes] /
 * [HandheldDeviceProfile.fanModeViaSettings] are now consistently gated on live node presence
 * for every device (`HandheldDeviceProfiles.resolve`), not just the ones that previously bothered
 * to check. This object's own admission logic needed no change: it already required
 * [fanNodeAvailable] and [fanModeViaSettings] and re-derived [supportsFanMode] independently, so
 * it stays a correct, unchanged triple-check over the widened inputs rather than a second place
 * that could drift from them.
 */
internal object FanMutationAdmissionPolicy {
    fun admits(
        deviceProfileId: String,
        fanNodeAvailable: Boolean,
        fanModeViaSettings: Boolean,
    ): Boolean = fanNodeAvailable &&
        fanModeViaSettings &&
        HandheldDeviceProfiles.supportsFanMode(deviceProfileId)
}

internal data class CpuTransportDecision(val path: String?, val reason: String) {
    /**
     * The node is there (or may be) but nothing readable came back: `NODE_UNREADABLE`, or `NODE_MISSING_OR_UNREADABLE`
     * when presence was not established. Not `NODE_MISSING`, which is a positive "no such node".
     */
    val nodeUnreadable: Boolean get() = reason == "NODE_UNREADABLE" || reason == "NODE_MISSING_OR_UNREADABLE"
}

/**
 * Snapdragon 8 Elite (CQ8725S/SM8750) shared CPU ceiling transport.
 *
 * AYN's `pservice` deliberately reasserts each cpufreq policy `scaling_max_freq`.
 * Qualcomm exposes a separate per-CPU QoS ceiling at this fixed node -- a platform node
 * common to the whole chip family, not a chassis-specific one; writing `cpuId:value`
 * there composes with the vendor owner instead of racing it. Selection is fail-closed and
 * only occurs for a device on this exact chip family with a complete, coherent CPU map.
 *
 * **2026-08-22 family-level policy:** gated on
 * [TweakerHardwareFamilies.resolve] membership, not a `deviceProfileId` allow-list, so any
 * AYN/Retroid handheld on this silicon gets the transport the Odin was measured with,
 * including a chassis nobody has qualified by name.
 *
 * **SUPERSEDED 2026-08-25: the gate is the NODE, not the family.** The paragraph above is kept
 * because the direction it set is the one that continued -- device id, then family, then
 * capability -- but its claim that family membership is what selects this transport stopped being
 * true in the same commit that wrote this sentence. The Nova (QCS8550, NOT 8-Elite) publishes a
 * complete, coherent map at this node; being outside the family is what kept it on
 * `scaling_max_freq` and produced its drift churn. See [selectedPath] for the device read and for
 * why removing the family conjunct relaxes nothing.
 */
internal object CpuCeilingTransport {
    const val ODIN_3_PROFILE = "ayn-odin3-cq8725s-pserver"
    const val ACTIVE_SHARED_QOS_MODE = HELD_CPU_QOS_MODE
    private val ENTRY = Regex("([0-9]{1,3}):([0-9]{1,10})")
    private val CPU_MAX_LOGICAL_ID = Regex("cpu:([0-9]+):scaling_max_freq")

    /**
     * The composition transport for this chassis, or null to keep per-policy `scaling_max_freq`.
     *
     * **Gated on the NODE, not on the chip family.** The prior gate moved from a device-id
     * allow-list to a family check; the family check was itself too narrow, and the Nova is what
     * proved it. Read live from a Retroid Pocket Nova (QCS8550, `retroid-qcs8550-pserver`) at
     * 2026-08-25T00:34Z:
     *
     * ```
     * $ cat /sys/kernel/msm_performance/parameters/cpu_max_freq
     * 0:2147483647 1:2147483647 ... 7:2147483647
     * ```
     *
     * Eight entries, one per CPU, unpinned -- the same shape the Odin's stock capture recorded. Every
     * condition below passes on it. The node was simply never READ there, because the probe that
     * feeds [rawQosMap] was family-gated too, so this function could only ever answer null and the
     * chassis was forced onto `scaling_max_freq` -- the node its vendor perf server reasserts, which
     * is the whole reason this transport exists (see this object's header). That is the entire
     * mechanism behind the Nova's `TARGET_CONTRACT_DRIFT_CPU` churn: 21 restores in four minutes
     * against zero on the Odin, on identical code.
     *
     * **Nothing here is relaxed -- only the family conjunct is removed.** Every coherence check below
     * still runs, and they are what makes this safe on hardware nobody has qualified: the node must
     * exist, parse completely, cover exactly the live policy map, and resolve to one distinct value
     * per policy. A chassis without it gets null and is byte-identical to before. A chassis where the
     * node exists but does not govern cannot silently do nothing either: `verifyPlanOnce` reads the
     * ceiling back through the SELECTED transport, so an inert node fails verification and the apply
     * refuses honestly rather than reporting a ceiling it never took.
     *
     * The `socModel` parameter is GONE rather than left unused. It existed only to feed the family
     * check, and a dead gate parameter still sitting in the signature is an invitation to wire the
     * gate back up without reading why it came out.
     */
    fun selectedPath(
        policies: List<CpuPolicy>,
        rawQosMap: String?,
    ): String? = decide(policies, rawQosMap).path

    /**
     * [selectedPath] with its REASON. The answer is identical (selectedPath delegates here); the only
     * addition is that a null no longer discards why. A silent demotion to the vendor-owned
     * `scaling_max_freq` transport cost the reference Nova a whole 09-30 session set before anyone could
     * say it had happened, so every refusal below has a name that reaches the per-session log line.
     */
    fun decide(
        policies: List<CpuPolicy>,
        rawQosMap: String?,
        nodePresent: Boolean? = null,
    ): CpuTransportDecision {
        if (policies.isEmpty()) return CpuTransportDecision(null, "NO_CPU_POLICIES")
        if (policies.any { it.cpuIds.isEmpty() }) return CpuTransportDecision(null, "POLICY_WITHOUT_CPUS")
        if (rawQosMap.isNullOrBlank()) {
            return CpuTransportDecision(
                null,
                if (nodePresent == true) "NODE_UNREADABLE" else if (nodePresent == false) "NODE_MISSING" else "NODE_MISSING_OR_UNREADABLE",
            )
        }
        val parsed = parse(rawQosMap) ?: return CpuTransportDecision(null, "NODE_UNPARSEABLE")
        val expectedCpuIds = policies.flatMap(CpuPolicy::cpuIds).toSet()
        if (expectedCpuIds.isEmpty() || parsed.keys != expectedCpuIds) {
            return CpuTransportDecision(
                null,
                "INCOHERENT_MAP_CPU_SET node=${parsed.keys.sorted()} expected=${expectedCpuIds.sorted()}",
            )
        }
        policies.firstOrNull { policyValue(parsed, it.cpuIds) == null }?.let { split ->
            return CpuTransportDecision(
                null,
                "INCOHERENT_MAP_POLICY_SPLIT policy=${split.id} values=${split.cpuIds.map { parsed[it] }}",
            )
        }
        return CpuTransportDecision(HardwareProbe.CPU_QOS_MAX_PATH, "OK")
    }

    /** The one per-session transport line: what was chosen and, if not SHARED_QOS, exactly why. */
    fun describeChoice(decision: CpuTransportDecision, reclaim: String? = null): String =
        "CPU_TRANSPORT chosen=" +
            (if (decision.path != null) "SHARED_QOS" else "POLICY_SCALING_MAX") +
            " reason=${decision.reason}" +
            (reclaim?.let { " reclaim=$it" }.orEmpty())

    fun parse(raw: String?): Map<Int, Int>? {
        val tokens = raw?.trim()?.takeIf(String::isNotEmpty)?.split(Regex("\\s+")) ?: return null
        val values = linkedMapOf<Int, Int>()
        tokens.forEach { token ->
            val match = ENTRY.matchEntire(token) ?: return null
            val cpuId = match.groupValues[1].toIntOrNull() ?: return null
            val value = match.groupValues[2].toLongOrNull()
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?: return null
            if (values.put(cpuId, value) != null) return null
        }
        return values.toSortedMap().toMap().takeIf { it.isNotEmpty() }
    }

    fun policyValue(raw: String?, cpuIds: List<Int>): Int? =
        parse(raw)?.let { policyValue(it, cpuIds) }

    fun policyValue(values: Map<Int, Int>, cpuIds: List<Int>): Int? {
        if (cpuIds.isEmpty() || cpuIds.size != cpuIds.toSet().size) return null
        val policyValues = cpuIds.map { values[it] ?: return null }.distinct()
        return policyValues.singleOrNull()
    }

    fun policyId(logicalId: String): Int? =
        CPU_MAX_LOGICAL_ID.matchEntire(logicalId)?.groupValues?.get(1)?.toIntOrNull()

    fun pathFor(policy: CpuPolicy, capabilities: HardwareCapabilities): String =
        capabilities.cpuCeilingPath ?: "${policy.path}/scaling_max_freq"

    fun isSharedQos(path: String): Boolean = path == HardwareProbe.CPU_QOS_MAX_PATH

    /**
     * Odin's vendor performance service rewrites the shared ceiling during foreground
     * transitions. Keep Mimir's active QoS target read-only, matching the proven
     * vendor-conflict posture used by handheld tuners. Stock restoration still uses
     * the mode captured in [HardwareSnapshot].
     */
    fun appliedMode(path: String, capturedMode: String?): String? =
        if (isSharedQos(path)) ACTIVE_SHARED_QOS_MODE else capturedMode
}

/**
 * Pure admission policy kept internal so host tests can prove dwell/boundary behavior
 * using read sequences without sysfs metadata or Android runtime access.
 */
internal object CpuWindowAdmissionPolicy {
    /**
     * **admit against the WINDOW, not against the instant.**
     *
     * This used to require the final two samples to be byte-identical and then admit against that one
     * still reading. On the Thor no attempt could ever clear it: the vendor perf HAL drives
     * `scaling_min_freq` continuously (policy0 measured oscillating 1459200 ↔ 1555200), so "did not
     * settle during bounded observation" was literally true forever and every profile apply was
     * refused `CPU_WINDOW_UNSETTLED`. Asking a live window to hold still is the wrong question.
     *
     * The aggregate answers the right one. Per policy the admission floor is the HIGHEST floor seen
     * across the dwell and the admission ceiling is the LOWEST ceiling seen, so the admitted target is
     * one the device will accept in **every** phase of the oscillation rather than in the phase that
     * happened to be showing.
     *
     * Highest and not lowest, and that is the whole point: **a ceiling below the live floor is not a
     * writable state.** The kernel raises `scaling_max_freq` to meet `scaling_min_freq`, the readback
     * then differs from the journalled contract and the apply dies `VERIFY_FAILED` — measured on the
     * Thor 2026-09-01, and recorded from the Nova before that. Clamping to the highest observed floor
     * is still an underclock: `min <= max` holds on any live policy, so it stays at or below the
     * device's own stock maximum. This preserves the preference for an admitted underclock
     * over a refusal that leaves the device at Stock.
     *
     * Structural faults are untouched — a wrong sample count, an invalid sample, a missing policy,
     * floor or ceiling all still refuse, because those mean the instrument is broken rather than the
     * window merely moving.
     */
    fun afterDwell(
        samples: List<Map<Int, CpuLiveWindowSample>>,
        targetsKhz: Map<Int, Int>,
        operatingPointsKhz: Map<Int, List<Int>> = emptyMap(),
    ): CpuWindowAdmission {
        if (samples.size != SAMPLE_COUNT) {
            return unsettled("cpu observation sample count ${samples.size} != $SAMPLE_COUNT")
        }
        if (samples.any { !isValid(it, targetsKhz.keys) }) {
            return unsettled("cpu live min/max invalid during bounded observation")
        }
        val observed = observedWindow(samples, targetsKhz.keys)
            ?: return unsettled("cpu live min/max never held one consistent window during observation")
        return targetAdmission(observed, targetsKhz, operatingPointsKhz)
    }

    /**
     * The band the window actually held across the dwell: floor = the highest floor seen, ceiling =
     * the lowest ceiling seen. Null when those cross — the policy never presented a single window a
     * target could be admitted against, which is a broken observation and not a clamp.
     */
    internal fun observedWindow(
        samples: List<Map<Int, CpuLiveWindowSample>>,
        policyIds: Set<Int>,
    ): Map<Int, CpuLiveWindowSample>? {
        val window = mutableMapOf<Int, CpuLiveWindowSample>()
        policyIds.forEach { policyId ->
            val mins = samples.mapNotNull { it[policyId]?.minKhz }
            val maxes = samples.mapNotNull { it[policyId]?.maxKhz }
            if (mins.size != samples.size || maxes.size != samples.size) return null
            val floor = mins.max()
            val ceiling = maxes.min()
            if (floor > ceiling) return null
            window[policyId] = CpuLiveWindowSample(minKhz = floor, maxKhz = ceiling)
        }
        return window
    }

    /**
     * Final capture-boundary read after the dwell. A floor/ceiling movement that now
     * conflicts with the requested ceiling gets its specific truthful code; all other
     * movement is an unsettled-window fault rather than a silently altered plan.
     */
    fun atCaptureBoundary(
        settled: Map<Int, CpuLiveWindowSample>,
        boundary: Map<Int, CpuLiveWindowSample>,
        targetsKhz: Map<Int, Int>,
        operatingPointsKhz: Map<Int, List<Int>> = emptyMap(),
    ): CpuWindowAdmission {
        if (!isValid(boundary, targetsKhz.keys)) {
            return unsettled("cpu live min/max invalid at final capture boundary")
        }
        // **Inside the observed band, not equal to it** . [settled] is
        // now the aggregate window from [afterDwell], so demanding equality here would fail on any
        // chassis whose floor moves — which is the one that ruling exists for.
        //
        // **The FLOOR half of that band stopped refusing on 2026-09-21**, policy *"fix it now on
        // the mechanism I have"*. The check is asymmetric by construction: the dwell gets eleven reads
        // and an aggregate, the boundary gets ONE, and that one has to fall inside the eleven. A fresh
        // draw from the same distribution can always be the new maximum, so this is a strictly harder
        // test than the dwell itself passes.
        //
        // Measured on the Thor, 2026-09-21, 222 reads of the shared QoS min requester over
        // forty seconds with the device idle:
        //
        //   policy0  672000 <-> 2016000     bursts 0.1-1.0 s
        //   policy3 1536000 <-> 2707200     resting gaps 1.1-6.1 s
        //
        // Six of eleven bursts were 0.2 s or shorter — shorter than [CPU_WINDOW_ADMISSION_SETTLE_MS],
        // so the dwell can alias straight past them — and one resting gap ran 6.1 s, three times the
        // dwell's own span. A dwell that lands inside such a gap aggregates only the resting floor, and
        // any burst at the boundary is then "outside everything the dwell saw".
        //
        // What makes dropping it safe is not the rate, it is that **nothing is derived from the floor
        // any more.** Since the two 2026-09-21 rulings, [targetAdmission] reads floor and ceiling
        // purely to check the INSTRUMENT: the plan comes from [snapToOperatingPoint] and the silicon's
        // own table. A floor that moved between the dwell and the boundary invalidates no number this
        // function produces, so refusing over it costs a profile apply and protects nothing.
        //
        // **The CEILING half stays, and is not symmetric with it.** The observed ceiling still has a
        // live consumer: `expectedValue = boundaryAdmission.values.getValue(policyId).maxKhz` journals
        // it as the readback expectation on the per-policy `scaling_max_freq` transport. A ceiling that
        // has genuinely dropped below what the dwell saw would journal an expectation the device can no
        // longer meet, and the apply would die `VERIFY_FAILED` later with a far worse message than this
        // one. The same trace measured the ceilings rock steady — 30 of 30 samples at stock — so this
        // half is not the coin toss the floor half is.
        //
        // Both bands are NAMED either way, refused or not. `logCpuWindowRefusal`'s own KDoc records
        // this trap twice: a message that "was constructed and then discarded, never logged" is what
        // made the Nova's apply refusals undiagnosable, and `stockCeilings` was added on 2026-09-19
        // because "a refusal that names one side of an inequality and not the other cannot be read from
        // outside the device". Suppressing a refusal must not suppress the observation either.
        val faults = mutableListOf<String>()
        val floorsAboveBand = mutableListOf<String>()
        // Every failing policy, not the first — policy 2026-08-23, the same reason
        // [targetAdmission] reports the whole picture.
        targetsKhz.keys.sorted().forEach { policyId ->
            val observed = settled[policyId]
            val now = boundary[policyId]
            if (observed == null || now == null) {
                faults += "cpu policy $policyId missing at final capture boundary"
                return@forEach
            }
            val observedFloor = observed.minKhz
            val observedCeiling = observed.maxKhz
            val liveFloor = now.minKhz
            val liveCeiling = now.maxKhz
            if (observedFloor == null || observedCeiling == null || liveFloor == null || liveCeiling == null) {
                faults += "cpu policy $policyId floor or ceiling missing at final capture boundary"
                return@forEach
            }
            val bands = "observed $observedFloor..$observedCeiling live $liveFloor..$liveCeiling"
            if (liveCeiling < observedCeiling) {
                faults += "cpu policy $policyId ceiling dropped below the observed window " +
                    "at final capture boundary ($bands)"
            } else if (liveFloor > observedFloor) {
                floorsAboveBand += "policy$policyId $bands"
            }
        }
        if (faults.isNotEmpty()) return unsettled(faults.joinToString("; "))
        if (floorsAboveBand.isNotEmpty()) {
            // Admitted, and said out loud. Without this line a chassis whose floor leaves the band on
            // every apply looks identical to one whose floor never moves, and the next reader has no
            // way to tell which — from outside the device there is no way to read `scaling_min_freq`
            // at all, on this chassis not even as the app (SELinux, measured 2026-09-21).
            runCatching {
                android.util.Log.i(
                    CLAMP_TAG,
                    "boundary floor above the observed window, admitted anyway: " +
                        floorsAboveBand.joinToString(", "),
                )
            }
        }
        // Admitted against the AGGREGATE, deliberately: the value journalled here has to be the one
        // the device accepts in every phase, not the one this instant happens to show.
        val targetAdmission = targetAdmission(settled, targetsKhz, operatingPointsKhz)
        if (targetAdmission.failures.isNotEmpty()) return targetAdmission
        // Propagate the clamp, not just the values: an earlier draft returned
        // `CpuWindowAdmission(values = boundary)` here and silently dropped `admittedTargetsKhz`,
        // so the boundary read -- the freshest window, and the one the caller journals from --
        // reported no clamp at all and the apply went out with the unclamped target.
        return CpuWindowAdmission(
            values = settled,
            code = targetAdmission.code,
            admittedTargetsKhz = targetAdmission.admittedTargetsKhz,
        )
    }

    /**
     * **2026-08-23: reports EVERY failing policy, not only the first.** It used to `return` on the
     * first policy outside its window, so `CPU_TARGET_BELOW_LIVE_FLOOR` named policy 3 on the Nova
     * and said nothing about 0 or 7 -- one masked refusal per attempt, and no way to tell a
     * single-cluster mismatch from a device-wide one without re-running blind. The failure list is
     * now the whole picture and every entry carries its policy's observed window, so one attempt
     * answers the question. `code` remains the FIRST failure's code, so every existing
     * `failures.isNotEmpty()` / code check behaves exactly as before; only the message got honest.
     *
     * Structural faults (a missing policy, floor or ceiling) still short-circuit as `unsettled`:
     * those mean the observation itself is not trustworthy, and continuing to compare targets
     * against it would be reporting conclusions drawn from a broken instrument.
     */
    /** Same tag the refusal path uses, so a clamp and a refusal read from one place. */
    private const val CLAMP_TAG = "MimirCpuWindowAdmission"

    /**
     * The highest operating point at or below [target] — or the lowest, when the target sits under the
     * whole table. Null when this policy published no table, and then there is nothing to snap onto and
     * the target passes through as asked.
     *
     * Downward, never upward: a CPU ceiling is a cap, and rounding a cap UP to the next operating point
     * would hand the profile more than it asked for.
     */
    /**
     * A policy's OWN published operating points, or empty when [detectCpuPolicies] had to synthesize a
     * two-entry stand-in from `cpuinfo_min_freq`/`cpuinfo_max_freq` because
     * `scaling_available_frequencies` could not be read — which happens: on the Thor, `shell`
     * cannot read `scaling_max_freq`/`scaling_min_freq` on policy0 at all (SELinux), and readability is
     * per file and per policy.
     *
     * **This guard is the difference between a ceiling plan and a disaster.** Snapping DOWN against a
     * table whose only entries are the policy's own minimum and maximum rounds every target between
     * them to the MINIMUM — every profile would pin the cluster at its slowest frequency, which is the
     * exact opposite of what the plan asked for. A stand-in is therefore treated as no table at all,
     * and the target passes through unsnapped.
     */
    internal fun publishedOperatingPointsKhz(policy: CpuPolicy): List<Int> {
        val standIn = listOf(policy.minKhz, policy.maxKhz).filter { it > 0 }.distinct().sorted()
        return if (policy.availableFrequenciesKhz.sorted() == standIn) emptyList() else policy.availableFrequenciesKhz
    }

    internal fun snapToOperatingPoint(operatingPointsKhz: List<Int>?, target: Int): Int? {
        val table = operatingPointsKhz?.filter { it > 0 }?.sorted()?.takeIf { it.isNotEmpty() } ?: return null
        return table.lastOrNull { it <= target } ?: table.first()
    }

    /**
     * The live ceiling no longer decides what may be admitted. The device's
     * own operating-point table does.
     *
     * What that retires is `CPU_TARGET_ABOVE_LIVE_CEILING`, and it was a self-locking refusal: the gate
     * read the live ceiling to decide what was admissible, so a clamp Mimir itself had written became
     * the evidence forbidding its own removal. The reference Thor, 2026-09-19, is the worked example —
     * `requested=balanced prior=stock` refused against `live window 1017600..1017600`, a window Mimir
     * had put there. Nothing the player could choose escaped it; only a reboot did.
     *
     * The operating-point table cannot fail that way, because it is a property of the silicon rather
     * than of Mimir's last write. A target off the table is snapped DOWN onto it rather than refused,
     * per the 2026-08-23 ruling that a refusal, which leaves the device at Stock, is never the safer
     * outcome.
     *
     * **The live FLOOR stopped deciding admission on 2026-09-21.** A
     * target below the floor used to be clamped up to it. That clamp was introduced on 2026-09-01 so
     * that any profile could apply at all, but the live floor is a transient, platform-driven min-frequency boost,
     * not a static device property. Measured again on the Thor 2026-09-21, 45
     * one-second samples with the device idle: 35 resting at `{0=672000, 3=1536000, 7=0}`, 10 burst to
     * `{0=2016000, 3=2707200, 7=0}` — the cluster maxima, in one-to-two second bursts every three to
     * five seconds. `observedWindow` takes `mins.max()`, so it almost always caught a burst and the
     * contract became a coin toss: runs 15 and 16, byte-identical APK and the same Balanced plan,
     * journalled `{0=2016000, 3=2707200, ...}` and `{0=1113600, 3=1651200, ...}`.
     *
     * What made retiring it safe rather than merely tidy is that the clamp only ever protected the
     * per-policy `scaling_max_freq` transport, where request and effective state are one file. On the
     * shared QoS node they are separate PM QoS requesters, measured 2026-09-21: Quiet and Balanced both
     * reached the node verbatim, and policy3's max request GOVERNED over a vendor min request nearly
     * twice as high — `scaling_max_freq` and `scaling_cur_freq` both followed it down. And no qualified
     * chassis is known to use the per-policy path: the Odin, Nova and Thor all read the shared node
     * all-`INT_MAX` at stock, and the fallback is reachable only on unqualified hardware or on a node
     * left stranded by an interrupted session. A chassis that does fall back now refuses honestly
     * instead of silently shipping three identical profiles.
     *
     * Floor and ceiling are both still READ, and deliberately: a policy that presents neither is a
     * broken observation, and admitting targets against a broken instrument is worse than refusing.
     * They are read to check the instrument, never to judge the target.
     */
    private fun targetAdmission(
        values: Map<Int, CpuLiveWindowSample>,
        targetsKhz: Map<Int, Int>,
        operatingPointsKhz: Map<Int, List<Int>>,
    ): CpuWindowAdmission {
        val admitted = mutableMapOf<Int, Int>()
        var firstCode: String? = null
        targetsKhz.toSortedMap().forEach { (policyId, rawTarget) ->
            val window = values[policyId] ?: return unsettled("cpu policy $policyId missing from observation")
            // Floor and ceiling are both read purely to check the INSTRUMENT. Neither decides a
            // target any more: the ceiling stopped deciding on 2026-09-21 (the operating-point ruling)
            // and the floor stopped the same evening (see this function's header). A policy that cannot
            // present both is a broken observation, and admitting against a broken instrument is worse
            // than refusing.
            window.minKhz ?: return unsettled("cpu policy $policyId floor missing")
            window.maxKhz ?: return unsettled("cpu policy $policyId ceiling missing")
            val onTable = snapToOperatingPoint(operatingPointsKhz[policyId], rawTarget)
            if (onTable != null && onTable != rawTarget) {
                admitted[policyId] = onTable
                if (firstCode == null) firstCode = HardwareProbe.CPU_TARGET_SNAPPED_TO_OPERATING_POINT
            }
        }
        // No `failures` branch here any more: since the 2026-09-21 rulings the only thing this loop
        // can do to a plan is snap it onto the silicon's own table, and the only refusals left are the
        // structural `unsettled(...)` returns above, which never reach this point.
        if (admitted.isNotEmpty()) {
            android.util.Log.i(
                CLAMP_TAG,
                "admitted: " + admitted.toSortedMap().entries.joinToString(", ") {
                    "policy${it.key} ${targetsKhz[it.key]}->${it.value}"
                },
            )
        }
        return CpuWindowAdmission(
            values = values,
            code = firstCode,
            admittedTargetsKhz = if (admitted.isEmpty()) emptyMap() else targetsKhz + admitted,
        )
    }

    private fun isValid(values: Map<Int, CpuLiveWindowSample>, policyIds: Set<Int>): Boolean =
        values.keys == policyIds && values.all { (_, window) ->
            val min = window.minKhz
            val max = window.maxKhz
            min != null && min > 0 && max != null && max > 0 && min <= max
        }

    private fun unsettled(detail: String): CpuWindowAdmission = CpuWindowAdmission(
        failures = listOf(detail),
        code = HardwareProbe.CPU_WINDOW_UNSETTLED,
    )

    /**
     * **Three, not eleven — the dwell stopped characterising and became a coherence check.**
     *
     * Eleven samples 200 ms apart was 2 s of observation, sized by policy L-1 (2026-09-01) to
     * span more than one phase of a vendor oscillation running about a second, so the AGGREGATE band
     * would describe the whole oscillation rather than whichever phase was showing. That mattered
     * because the band steered the target: a ceiling was refused against the live ceiling, and a
     * target below the live floor was clamped up to it.
     *
     * Both were retired on 2026-09-21 — the operating-point table decides what may be written, and
     * the live-floor clamp is gone. What still reads this dwell is:
     *
     *   - [isValid], per sample: every planned policy present, floor and ceiling positive, floor not
     *     above ceiling. A structural question about the INSTRUMENT, answered by any one sample.
     *   - [targetAdmission], which uses floor and ceiling only for `?: return unsettled(...)`
     *     non-null checks; the admitted value comes from [snapToOperatingPoint] and the raw target,
     *     neither of which changes between sample 1 and sample 11.
     *   - [atCaptureBoundary]'s surviving ceiling half, which compares the boundary read against
     *     `maxes.min()`. The ceilings were measured steady on the Thor 2026-09-21, 30 of 30 samples
     *     at stock, so a shorter dwell loosens a check on something that was not oscillating.
     *
     * So the dwell was paying two seconds, twice per apply, to characterise a band nothing
     * characterises against any more. This is the cut [CPU_FLOOR_SETTLE_ROUNDS] already made for the
     * same reason — sized for what reads it, not for what used to.
     *
     * **If [TuningPlanner.CPU_SETTLED_FLOOR_STEERS_DERIVATION] is ever re-armed, this goes back to
     * eleven first.** A three-sample band is not a characterisation, and steering derivation from one
     * would reintroduce the coin toss that retired the clamp. `CpuDwellSizingTest` fails if the flag
     * is on while this sits below [CHARACTERISING_SAMPLE_COUNT].
     */
    const val SAMPLE_COUNT = 3

    /**
     * What [SAMPLE_COUNT] has to be for the dwell to describe an oscillation rather than sample one
     * phase of it: eleven at 200 ms, spanning 2 s against phases of about a second. Named so the
     * guard above can state its condition instead of restating a literal.
     */
    const val CHARACTERISING_SAMPLE_COUNT = 11

    /**
     * Retired with policy L-1 (2026-09-01) and kept only as a marker of what it used to mean:
     * admission required the final two samples to read identically. Nothing consumes it now —
     * admission is against the whole observed window — and it is left here rather than deleted so a
     * reader of the old device evidence can find the number it refers to.
     */
    @Deprecated("Admission aggregates the whole dwell; no final-pair stillness is required (L-1).")
    const val FINAL_CONSECUTIVE_SAMPLES = 2
}

internal class HardwareProbe(
    private val context: Context,
    private val reader: SystemReader,
    private val pServer: PServerClient,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    /**
     * Live pServer availability. A seam rather than a direct `pServer.isAvailable` read because it
     * is the cache key for [StaticHardwareTopology] — a transition has to re-probe, and a rule that
     * cannot be exercised in a test is a rule nobody can show still holds.
     */
    private val pServerAvailable: () -> Boolean = { pServer.isAvailable },
    /**
     * This boot's stock restore point. A seam because the rule it enforces -- first capture of a
     * boot defines stock, no later capture may redefine it -- is the whole fix for the 2026-09-19
     * Thor strand, and a rule that cannot be exercised in a test is a rule nobody can show holds.
     */
    private val stockCeilingBaselines: StockCeilingBaselineStore =
        StockCeilingBaselineStore(File(context.filesDir, StockCeilingBaselineStore.DIRECTORY)),
    /**
     * The player's own `performance_mode`, remembered across sessions and reboots
     * ([VendorModeOriginRecord]). Rooted in the same directory class as [stockCeilingBaselines]: the
     * service hands this probe its DEVICE-PROTECTED context, so the daemon and the guardian process
     * (same APK, same files dir) read one record. Exposed as [vendorModeOriginStore] so the command
     * port saves and clears the SAME store the capture reads.
     */
    val vendorModeOriginStore: VendorModeOriginStore =
        VendorModeOriginStore(File(context.filesDir, VendorModeOriginStore.DIRECTORY)),
    /** [VENDOR_MODE_TRANSPORT_ENABLED] by default; a constructor argument so tests never lean on the shipped value. */
    private val vendorModeTransportEnabled: Boolean = VENDOR_MODE_TRANSPORT_ENABLED,
    /**
     * The direct `Settings.System` read of `performance_mode`: immediate and authoritative, no
     * pserver round trip. A seam so a presence/capture rule can be exercised without a ContentResolver.
     */
    private val vendorModeSettingReader: () -> String? = {
        Settings.System.getString(context.contentResolver, "performance_mode")
    },
    /**
     * The AYN coupling flag, `is_quick_set_performance_and_fan_enable`: when it reads exactly "1",
     * SystemUI rewrites `fan_mode` on every `performance_mode` change ([VendorFanCouplingSettle]).
     * A direct read like [vendorModeSettingReader]; the key is never written by Mimir.
     */
    private val vendorFanCouplingSettingReader: () -> String? = {
        Settings.System.getString(context.contentResolver, "is_quick_set_performance_and_fan_enable")
    },
) {
    /** Thermal-zone names/paths are boot-stable; only their temperature values are live. */
    @Volatile
    private var thermalZoneTopologyCache: List<ThermalZoneTopology>? = null

    private data class ThermalZoneTopology(
        val type: String,
        val path: String,
    )

    /**
     * The parts of [probe] that describe what this silicon *is* rather than what it is *doing*.
     *
     * `getTelemetry()` re-ran every one of these on every tick — the cpufreq directory walk plus
     * four reads per policy, the GPU OPP table, the device-profile resolve, and
     * `DisplayManager.supportedModes`, which is a Binder into system_server. None of them can
     * change while the process lives, so the tick was paying for a hardware inventory once a second
     * to be told the same answer (RC-47).
     *
     * Same shape and same reasoning as [thermalZoneTopologyCache] one field up: cache the topology,
     * keep every value live.
     *
     * **What is deliberately NOT in here, and why.** Everything that gates a mutation stays live and
     * uncached, because a frozen capability set would silently offer or withhold the wrong axis:
     * `pServer.isAvailable`, the GPU/fan node existence checks, the Odin QoS-map read behind
     * `cpuCeilingPath`, per-policy `scaling_cur_freq`, and the whole qualification/posture/dry-run
     * composition in `MimirTweakerService.capabilities()`. Those are a handful of small reads;
     * the inventory above was the cost.
     */
    private data class StaticHardwareTopology(
        val socModel: String,
        val hardwareFamilyId: String,
        val profileTreatment: String?,
        val deviceProfile: HandheldDeviceProfile,
        /** `currentKhz` is a placeholder here; [probe] refreshes it live on every call. */
        val cpuPolicyTopology: List<CpuPolicy>,
        /**
         * Per-policy settled live `scaling_min_freq`. Cached with the rest of the inventory
         * deliberately: this floor is chassis behaviour, not a per-tick reading -- measured stable
         * on the Nova across five hours and unchanged at `performance_mode` 0 and 1 -- and reading
         * it costs a bounded settle, which must not land on every capability probe. If it does move,
         * the apply-time dwell in [CpuWindowAdmissionPolicy] is the independent verifier and still
         * refuses; this snapshot only steers derivation.
         */
        val cpuSettledFloorsKhz: Map<Int, Int>,
        val gpuFrequenciesKhz: List<Int>,
        val refreshRatesHz: List<Int>,
        /**
         * The pServer state this inventory was discovered under. [SystemReader] falls back to
         * [PServerProtectedNodeAccess] for protected cpufreq discovery, so a topology found while
         * pServer was down can be empty or partial — a later transition must re-probe rather than
         * serve that as fact.
         */
        val pServerAvailable: Boolean,
    )

    @Volatile
    private var staticTopologyCache: StaticHardwareTopology? = null

    /** Last identity line written, so the probe logs transitions rather than every tick. */
    @Volatile
    private var lastLoggedIdentity: String? = null

    /**
     * Drop the cached inventory. Called on the one runtime event that can legitimately change it —
     * a display being added, removed or re-moded, which moves `refreshRatesHz` and therefore
     * `hardwareFingerprint`. Wired from the service, which owns a main looper to register the
     * `DisplayManager.DisplayListener` on.
     */
    fun invalidateStaticTopology() {
        synchronized(this) { staticTopologyCache = null }
    }

    /**
     * Drop the cache only if the display mode table genuinely moved.
     *
     * `DisplayManager.DisplayListener.onDisplayChanged` fires on every refresh-rate switch and
     * every rotation — neither of which changes `supportedModes` — so invalidating on the callback
     * alone would re-probe the whole inventory several times a minute and hand the saving straight
     * back. This does the one Binder read the callback is about and compares.
     */
    fun invalidateStaticTopologyIfDisplaysChanged() {
        val cached = staticTopologyCache ?: return
        val current = readRefreshRatesHz()
        // An empty read is a transient service hiccup, not the built-in panel vanishing.
        if (current.isNotEmpty() && current != cached.refreshRatesHz) invalidateStaticTopology()
    }

    private fun readRefreshRatesHz(): List<Int> =
        context.getSystemService(DisplayManager::class.java)
            ?.displays
            ?.flatMap { display -> display.supportedModes.map { it.refreshRate.toInt() } }
            ?.distinct()
            ?.sorted()
            .orEmpty()

    private fun staticTopology(pServerAvailable: Boolean): StaticHardwareTopology {
        staticTopologyCache
            ?.takeIf { it.pServerAvailable == pServerAvailable }
            ?.let { return it }
        return synchronized(this) {
            staticTopologyCache
                ?.takeIf { it.pServerAvailable == pServerAvailable }
                ?: discoverStaticTopology(pServerAvailable).also { staticTopologyCache = it }
        }
    }

    /**
     * Bounded-settle read of each policy's live `scaling_min_freq`, using the SAME sample count and
     * dwell the apply-time window admission uses ([CpuWindowAdmissionPolicy.SAMPLE_COUNT] /
     * [CPU_WINDOW_SETTLE_MS]) -- one discipline for one question, so the value derivation trusts and
     * the value admission enforces cannot be settled by two different standards.
     *
     * A policy is OMITTED (not defaulted) unless every sample agrees and the value is plausible:
     * positive, within the policy's own immutable capability range, and an actual member of its OPP
     * table. Omission means "floor unknown", and [TuningPlanner.liveOpps] then behaves exactly as it
     * did before this existed. Fail-closed, never fail-quiet: an unreadable floor must not silently
     * become a guessed one.
     */
    private fun readSettledCpuFloorsKhz(policies: List<CpuPolicy>): Map<Int, Int> {
        if (policies.isEmpty()) return emptyMap()
        // Sampled round-robin -- every policy read once per round -- so total cost is
        // (rounds - 1) * CPU_WINDOW_SETTLE_MS regardless of how many clusters a chassis has,
        // rather than that much again per policy. See CPU_FLOOR_SETTLE_ROUNDS for why the count
        // is not CpuWindowAdmissionPolicy.SAMPLE_COUNT.
        val rounds = (0 until CPU_FLOOR_SETTLE_ROUNDS).map { round ->
            if (round > 0) sleeper(CPU_WINDOW_SETTLE_MS)
            policies.associate { policy ->
                policy.id to reader.read("${policy.path}/scaling_min_freq")?.toIntOrNull()
            }
        }
        return buildMap {
            policies.forEach { policy ->
                val settled = rounds.first()[policy.id]
                if (settled == null || rounds.any { it[policy.id] != settled }) return@forEach
                if (settled !in policy.minKhz..policy.maxKhz) return@forEach
                if (settled !in policy.availableFrequenciesKhz) return@forEach
                put(policy.id, settled)
            }
        }
    }

    private fun discoverStaticTopology(pServerAvailable: Boolean): StaticHardwareTopology {
        val soc = systemProperty("ro.soc.model")
            ?: systemProperty("ro.vendor.qti.soc_model")
            ?: "unknown"
        val hardwareFamily = TweakerHardwareFamilies.resolve(soc)
        val policies = detectCpuPolicies()
        return StaticHardwareTopology(
            socModel = soc,
            hardwareFamilyId = hardwareFamily.id,
            profileTreatment = hardwareFamily.profileTreatment,
            deviceProfile = HandheldDeviceProfiles.resolve(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                socModel = soc,
                reader = reader,
            ),
            cpuPolicyTopology = policies,
            cpuSettledFloorsKhz = readSettledCpuFloorsKhz(policies),
            gpuFrequenciesKhz = readFrequencyList(GPU_FREQUENCIES)
                .ifEmpty { readFrequencyList(GPU_DEVFREQ_FREQUENCIES) }
                .map { hz -> (hz / 1_000L).toInt() }
                .filter { it > 0 }
                .distinct()
                .sorted(),
            refreshRatesHz = readRefreshRatesHz(),
            pServerAvailable = pServerAvailable,
        )
    }

    fun probe(): HardwareCapabilities {
        val pServerAvailable = pServerAvailable()
        val static = staticTopology(pServerAvailable)
        val soc = static.socModel
        val deviceProfile = static.deviceProfile
        // Topology is cached; the one live field on it is not. `currentKhz` is read every call so
        // a value literally named "current" is never served frozen.
        val policies = static.cpuPolicyTopology.map { policy ->
            policy.copy(currentKhz = reader.read("${policy.path}/scaling_cur_freq")?.toIntOrNull() ?: 0)
        }
        // [sharedQosFamily] no longer decides whether the node is READ -- only whether a member of
        // that family is allowed to fall back to per-policy when the node is missing (the axis guard
        // below). 2026-08-25: the read is unconditional, because a family-gated read made
        // CpuCeilingTransport.selectedPath unable to answer anything but null off the 8-Elite, which
        // is what forced the Nova onto the node its vendor reasserts. One bounded read per topology
        // cache fill on a chassis that turns out not to have it.
        val sharedQosFamily = isSnapdragon8EliteFamily(soc)
        val cpuCeilingPath = CpuCeilingTransport.selectedPath(
            policies = policies,
            rawQosMap = reader.read(CPU_QOS_MAX_PATH),
        )
        val gpuFrequencies = static.gpuFrequenciesKhz
        val refreshRates = static.refreshRatesHz
        // Node existence stays live even though the OPP table is cached: this is what admits the
        // GPU tuning axis, and an axis must never be offered on the strength of a stale read.
        val gpuAvailable = reader.exists(GPU_MIN_LEVEL) && reader.exists(GPU_MAX_LEVEL) && gpuFrequencies.size >= 2
        val fanNodes = deviceProfile.fanNodes
        val fanAvailable = fanNodes != null && HandheldDeviceProfiles.fanPresent(reader, fanNodes)
        val classification = HardwareClassifier.classify(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socModel = soc,
            deviceProfileId = deviceProfile.id,
            pServerAvailable = pServerAvailable,
            cpuPolicyIds = policies.map { it.id }.toSet(),
            gpuNodeAvailable = gpuAvailable,
            fanNodeAvailable = fanAvailable,
            refreshRatesHz = refreshRates,
        )
        // The vendor performance-mode axis is a CAPABILITY of this unit, never a model test: the key
        // reads as one of the known modes AND pserver is attested (the ODM `pservice` that consumes
        // the key is what that attestation proves). The family only selects the mode TABLE later.
        // Held back entirely while [vendorModeTransportEnabled] is false -- see
        // [VENDOR_MODE_TRANSPORT_ENABLED]: the key outlives a reboot, so no write path may exist
        // unless the Guardian can restore it (RestoreGuardianVendorModePutEntry, shipped on).
        val vendorModePresent = vendorModeTransportEnabled &&
            pServerAvailable &&
            readVendorModeLive() != null
        val supportedAxes = buildSet {
            // Family-scoped 2026-08-22 (late): an 8-Elite chassis with no coherent shared-QoS CPU
            // map gets NO CPU axis rather than silently falling back to unowned per-policy
            // scaling_max_freq -- the same fail-closed posture the exact Odin id used to get alone,
            // now extended to every family member instead of relaxed for them. A non-8-Elite device
            // is unaffected: it was never gated on cpuCeilingPath.
            if (
                policies.isNotEmpty() &&
                (!sharedQosFamily || cpuCeilingPath != null)
            ) {
                add(TuningAxis.CPU)
            }
            if (gpuAvailable) add(TuningAxis.GPU)
            if (FanMutationAdmissionPolicy.admits(
                    deviceProfileId = deviceProfile.id,
                    fanNodeAvailable = fanAvailable,
                    fanModeViaSettings = deviceProfile.fanModeViaSettings,
                )
            ) {
                add(TuningAxis.FAN)
            }
            if (vendorModePresent) add(TuningAxis.VENDOR_MODE)
        }
        // TW04-A: local/lab CERTIFIED reports never populate product-qualified IDs or promote SUPPORTED.
        val capabilities = HardwareCapabilities(
            status = classification.first,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socModel = soc,
            buildFingerprint = Build.FINGERPRINT,
            deviceProfileId = deviceProfile.id,
            supportedAxes = supportedAxes,
            pServerAvailable = pServerAvailable,
            cpuPolicies = policies,
            gpuFrequenciesKhz = gpuFrequencies,
            gpuNodeAvailable = gpuAvailable,
            fanNodeAvailable = fanAvailable,
            refreshRatesHz = refreshRates,
            notes = classification.second,
            certifiedProfileIds = TweakerPostureTruth.productQualifiedProfileIds(),
            supportedUncertifiedProfileIds = SupportedUncertifiedProfiles.compute(
                status = classification.first,
                socModel = soc,
                cpuPolicies = policies,
            ),
            posture = TweakerPostureTruth.posture,
            qualificationSource = TweakerPostureTruth.qualificationSource(
                certificationStatusProvider.hasLocalCalibrationReport(),
            ),
            hardwareFamilyId = static.hardwareFamilyId,
            profileTreatment = static.profileTreatment,
            cpuCeilingPath = cpuCeilingPath,
            // The GPU's counterpart, discovered here and only here. The plan's target level is not
            // known yet, so the table's own top row stands in: if level 0 cannot be translated,
            // no level can, and the chassis is not devfreq-capable at all.
            gpuCeilingPath = GpuCeilingTransport.selectedPath(
                gpuFrequenciesKhz = gpuFrequencies,
                powerLevel = 0,
                devfreqMaxExists = reader.exists(GPU_DEVFREQ_MAX_FREQ),
            ),
            // Live, non-identity, and read from the cached inventory rather than per probe --
            // see StaticHardwareTopology.cpuSettledFloorsKhz for why it is cached and what still
            // verifies it independently at apply time.
            cpuSettledFloorsKhz = static.cpuSettledFloorsKhz,
            vendorPerformanceModePresent = vendorModePresent,
        )
        // Nova bring-up 2026-08-21. PRODUCTION compiles out every diagnostic surface, so on a
        // device with no signed manifest there was NO way to tell the two causes of STOCK ONLY
        // apart on hardware: `UNSUPPORTED` (pserver or cpufreq missing -- the transport is dead)
        // and `SUPPORTED` (transport fine; any manifest-less profile admission is per plan).
        // `tweakerUtilityAvailability` collapses both to RESPONDING_STOCK_ONLY, so the bridge log
        // cannot distinguish them either. This also surfaces the runtime `hardwareFingerprint`,
        // which SIGNING.md's request grammar requires verbatim and which production could not
        // otherwise report. Observability only: no control flow, and deliberately NOT a new bundle
        // key -- TweakerCapabilityContract's drift tests redden on any key not classified there.
        //
        // RC-47: this used to fire on every telemetry tick, taking a SHA-256 over the whole
        // capability set and writing a line to logcat once a second forever. It now fires on the
        // first probe and on every subsequent CHANGE to the identity summary -- which includes the
        // pServer flip and the display-topology invalidation, the two events that can move the
        // fingerprint -- so no transition is lost and the steady state is silent.
        val identity = "identity status=${capabilities.status} profile=${capabilities.deviceProfileId} " +
            "family=${capabilities.hardwareFamilyId}/${capabilities.profileTreatment} " +
            "pserver=${capabilities.pServerAvailable} " +
            "cpuPolicies=${capabilities.cpuPolicies.size} gpu=${capabilities.gpuNodeAvailable} " +
            "fan=${capabilities.fanNodeAvailable} posture=${capabilities.posture} " +
            "axes=${capabilities.supportedAxes} " +
            "certified=${capabilities.certifiedProfileIds} " +
            "uncertified=${capabilities.supportedUncertifiedProfileIds}"
        if (lastLoggedIdentity != identity) {
            lastLoggedIdentity = identity
            // The fingerprint is a SHA-256 over the whole capability set, so it is computed only
            // on the line that will actually be written.
            Log.i(IDENTITY_TAG, "$identity fingerprint=${HardwareFingerprint.create(capabilities)}")
        }
        return capabilities
    }

    fun telemetry(capabilities: HardwareCapabilities): TelemetrySnapshot {
        // One thermal sweep feeds the light snapshot AND the witnesses: cpuTempC, gpuTempC and
        // cpuPackageTempC must describe the same sample instant. Two sweeps here previously left
        // cpuPackageTempC holding sweep-1 values while cpuTempC/gpuTempC came from sweep 2,
        // under a single timestamp.
        val thermalZones = readClassifiedThermalZones()
        val light = lightTelemetry(capabilities, thermalZones)
        val thermalWitness = thermalWitnesses(thermalZones)
        val cpuCurrent = capabilities.cpuPolicies.associate { it.id to readInt("${it.path}/scaling_cur_freq") }
        val cpuMin = capabilities.cpuPolicies.associate { it.id to readInt("${it.path}/scaling_min_freq") }
        val cpuMax = capabilities.cpuPolicies.associate { it.id to readInt("${it.path}/scaling_max_freq") }
        // Family, not device: any 8-Elite chassis requires the shared QoS transport.
        val requiresSharedQos = isSnapdragon8EliteFamily(capabilities)
        val sharedQosAvailable = capabilities.cpuCeilingPath == HardwareProbe.CPU_QOS_MAX_PATH
        val cpuQosCeilings = capabilities.cpuPolicies.mapNotNull { policy ->
            val path = CpuCeilingTransport.pathFor(policy, capabilities)
            val effectiveCeiling = if (CpuCeilingTransport.isSharedQos(path)) {
                CpuCeilingTransport.policyValue(reader.read(path), policy.cpuIds)
            } else {
                cpuMax[policy.id]
            }
            effectiveCeiling?.takeIf { it > 0 }?.let { policy.id to it }
        }.toMap().takeUnless { requiresSharedQos && !sharedQosAvailable }.orEmpty()
        return light.copy(
            cpuCurrentKhz = cpuCurrent,
            cpuMinKhz = cpuMin,
            cpuMaxKhz = cpuMax,
            gpuClockHz = reader.read(GPU_CLOCK)?.toLongOrNull(),
            gpuMinPowerLevel = reader.read(GPU_MIN_LEVEL)?.toIntOrNull(),
            gpuMaxPowerLevel = reader.read(GPU_MAX_LEVEL)?.toIntOrNull(),
            // Read in the SAME units the contract stores and the derate law is written in for this
            // id -- kHz. Two units for one quantity is how a sign gets lost.
            gpuDevfreqMaxHz = reader.read(GPU_DEVFREQ_MAX_FREQ)?.trim()?.toLongOrNull()?.toInt(),
            fanDuty = HandheldDeviceProfiles.resolve(
                manufacturer = capabilities.manufacturer,
                model = capabilities.model,
                socModel = capabilities.socModel,
                reader = reader,
            ).fanNodes?.dutyPath?.let { reader.read(it)?.toIntOrNull() },
            fanPeriod = HandheldDeviceProfiles.resolve(
                manufacturer = capabilities.manufacturer,
                model = capabilities.model,
                socModel = capabilities.socModel,
                reader = reader,
            ).fanNodes?.periodPath?.let { reader.read(it)?.toIntOrNull() },
            vendorPerformanceMode = Settings.System
                .getString(context.contentResolver, "performance_mode")
                ?.toIntOrNull(),
            vendorFanMode = fanModeRaw()?.toIntOrNull(),
            cpuQosCeilingKhz = cpuQosCeilings,
            cpuQosTransport = if (sharedQosAvailable) {
                CpuQosTransport.SHARED_QOS
            } else if (requiresSharedQos) {
                CpuQosTransport.UNKNOWN
            } else {
                CpuQosTransport.POLICY_SCALING_MAX
            },
            bootId = reader.read(BOOT_ID_PATH)?.trim()?.takeIf(String::isNotEmpty),
            cpuTempC = thermalWitness.cpuWinner?.celsius,
            gpuTempC = thermalWitness.gpuWinner?.celsius,
            cpuThermalZone = thermalWitness.cpuWinner,
            gpuThermalZone = thermalWitness.gpuWinner,
            thermalInventory = thermalWitness.inventory,
        )
    }

    /** No policy/QoS reads: safe for the regular sampling cadence. */
    fun lightTelemetry(
        capabilities: HardwareCapabilities,
        classifiedThermalZones: List<ClassifiedThermalZone> = readClassifiedThermalZones(),
    ): TelemetrySnapshot {
        val fanNodes = HandheldDeviceProfiles.resolve(
            manufacturer = capabilities.manufacturer,
            model = capabilities.model,
            socModel = capabilities.socModel,
            reader = reader,
        ).fanNodes
        val fanDutyPath = fanNodes?.dutyPath
        val fanPeriodPath = fanNodes?.periodPath
        // NOT speedPath: that node is an existence witness, not a sensor. See
        // FanNodes.tachometerPath -- currently null on every supported handheld, so fanRpm is
        // honestly unknown rather than a fabricated number.
        val fanSpeedPath = fanNodes?.tachometerPath
        val thermalZones = classifiedThermalZoneWinners(classifiedThermalZones)
        val packageTemperature = cpuPackageTemperatureWitness(classifiedThermalZones, capabilities)
        val batteryCurrentUa = readNullableInt(BATTERY_CURRENT_UA)
        val batteryVoltageMv = readNullableInt(BATTERY_VOLTAGE_UV)?.div(1_000)
        val batteryPowerMw = if (batteryCurrentUa != null && batteryVoltageMv != null) {
            ((batteryCurrentUa.toLong() * batteryVoltageMv.toLong()) / 1_000_000L)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            null
        }
        return TelemetrySnapshot(
            cpuCurrentKhz = emptyMap(),
            cpuMinKhz = emptyMap(),
            cpuMaxKhz = emptyMap(),
            gpuClockHz = null,
            gpuBusyPercent = gpuBusyPercent(),
            gpuMinPowerLevel = null,
            gpuMaxPowerLevel = null,
            fanDuty = fanDutyPath?.let { reader.read(it)?.toIntOrNull() },
            fanPeriod = fanPeriodPath?.let { reader.read(it)?.toIntOrNull() },
            fanRpm = fanSpeedPath?.let { reader.read(it)?.toIntOrNull() },
            cpuTempC = thermalZones.first?.celsius,
            gpuTempC = thermalZones.second?.celsius,
            batteryLevelPercent = readNullableInt(BATTERY_CAPACITY),
            batteryCurrentUa = batteryCurrentUa,
            batteryVoltageMv = batteryVoltageMv,
            batteryPowerMw = batteryPowerMw,
            batteryTempDeciC = readNullableInt(BATTERY_TEMP),
            batteryStatus = reader.read(BATTERY_STATUS),
            externalPowerState = externalPowerState(),
            thermalStatus =
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus,
            vendorPerformanceMode = null,
            vendorFanMode = fanModeRaw()?.toIntOrNull(),
            cpuThermalZone = thermalZones.first,
            gpuThermalZone = thermalZones.second,
            cpuQosCeilingKhz = emptyMap(),
            cpuQosTransport = CpuQosTransport.UNKNOWN,
            bootId = null,
            cpuPackageTempC = packageTemperature?.celsius,
            cpuPackageTempZoneCount = packageTemperature?.zoneCount,
        )
    }

    /**
     * Family, not device (2026-08-22 late ruling): the 8-Elite platform thermal-zone naming this
     * gates ([ODIN_3_PACKAGE_THERMAL_PREFIXES], [CPU_QOS_MAX_PATH]'s shared QoS transport) is
     * exposed by the Qualcomm reference device tree for the whole chip family, not authored per
     * chassis -- the Odin is the anchor hardware that measured it, not the boundary of who has it.
     * Display-projection and curve-bias inputs only (see call sites' own KDocs); the Guardian
     * 105 C trip and `THERMAL_STATUS_SEVERE` read `cpuTempC`/`gpuTempC` from the classified
     * CPU/GPU zone families, never this witness, and are unaffected by this predicate.
     */
    private fun isSnapdragon8EliteFamily(capabilities: HardwareCapabilities): Boolean =
        isSnapdragon8EliteFamily(capabilities.socModel)

    private fun isSnapdragon8EliteFamily(socModel: String): Boolean =
        TweakerHardwareFamilies.resolve(socModel).id == TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID

    /** Display projection only; supervisor/fan actuation keeps its strict exact-sensor path. */
    private fun cpuPackageTemperatureWitness(
        zones: List<ClassifiedThermalZone>,
        capabilities: HardwareCapabilities,
    ): CpuPackageTemperatureWitness? = classifiedCpuPackageTemperatureWitness(
        zones = zones,
        preferredTypePrefixes = if (isSnapdragon8EliteFamily(capabilities)) {
            ODIN_3_PACKAGE_THERMAL_PREFIXES
        } else {
            emptyList()
        },
    )

    /**
     * One monitor-loop sample: light thermals/fan truth plus only the nodes required by
     * the authenticated applied-target contract. This deliberately avoids rebuilding
     * hardware capabilities or rereading the complete calibration inventory every tick.
     */
    fun supervisorTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot {
        val fanNodes = HandheldDeviceProfiles.resolve(
            manufacturer = capabilities.manufacturer,
            model = capabilities.model,
            socModel = capabilities.socModel,
            reader = reader,
        ).fanNodes
        val classifiedThermalZones = readSupervisorThermalZones(capabilities)
        val thermalZones = classifiedThermalZoneWinners(classifiedThermalZones)
        val strictPackageThermal = if (isSnapdragon8EliteFamily(capabilities)) {
            strictOdinPackageThermalReading(classifiedThermalZones)
        } else {
            null
        }
        val sharedQosAvailable = capabilities.cpuCeilingPath == CPU_QOS_MAX_PATH
        val sharedQosRaw = if (sharedQosAvailable) reader.read(CPU_QOS_MAX_PATH) else null
        val cpuQosCeilings = capabilities.cpuPolicies.mapNotNull { policy ->
            val path = CpuCeilingTransport.pathFor(policy, capabilities)
            val effectiveCeiling = if (CpuCeilingTransport.isSharedQos(path)) {
                CpuCeilingTransport.policyValue(sharedQosRaw, policy.cpuIds)
            } else {
                reader.read(path)?.toIntOrNull()
            }
            effectiveCeiling?.takeIf { it > 0 }?.let { policy.id to it }
        }.toMap()
        return TelemetrySnapshot(
            cpuCurrentKhz = emptyMap(),
            cpuMinKhz = emptyMap(),
            cpuMaxKhz = emptyMap(),
            gpuClockHz = null,
            gpuBusyPercent = null,
            gpuMinPowerLevel = reader.read(GPU_MIN_LEVEL)?.toIntOrNull(),
            gpuMaxPowerLevel = reader.read(GPU_MAX_LEVEL)?.toIntOrNull(),
            // The supervisor's OWN telemetry builder. The first one was patched and this one was
            // not, so the watch compared a ceiling it never read: `expected=475000000Hz observed=missing`.
            gpuDevfreqMaxHz = reader.read(GPU_DEVFREQ_MAX_FREQ)?.trim()?.toLongOrNull()?.toInt(),
            fanDuty = fanNodes?.dutyPath?.let { reader.read(it)?.toIntOrNull() },
            fanPeriod = fanNodes?.periodPath?.let { reader.read(it)?.toIntOrNull() },
            fanRpm = fanNodes?.tachometerPath?.let { reader.read(it)?.toIntOrNull() },
            cpuTempC = thermalZones.first?.celsius,
            gpuTempC = thermalZones.second?.celsius,
            batteryLevelPercent = null,
            batteryCurrentUa = null,
            batteryVoltageMv = null,
            batteryPowerMw = null,
            batteryTempDeciC = readNullableInt(BATTERY_TEMP),
            batteryStatus = null,
            thermalStatus =
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus,
            // The monitor loop's sample. The supervisor's applied-target contract carries the mode this
            // session wrote and watches it for a player's change (a yield); an applied mode with no
            // sample is an unavailable channel and, after a few ticks, a Restore. So this MUST be read
            // here -- but only where the axis is admitted: elsewhere there is no contract leg to feed
            // and the field stays null, exactly as before the axis existed.
            vendorPerformanceMode = if (TuningAxis.VENDOR_MODE in capabilities.supportedAxes) {
                vendorModeRaw()?.trim()?.toIntOrNull()
            } else {
                null
            },
            vendorFanMode = fanModeRaw()?.toIntOrNull(),
            cpuThermalZone = thermalZones.first,
            gpuThermalZone = thermalZones.second,
            cpuQosCeilingKhz = cpuQosCeilings,
            cpuQosTransport = if (sharedQosAvailable) {
                CpuQosTransport.SHARED_QOS
            } else {
                CpuQosTransport.POLICY_SCALING_MAX
            },
            bootId = reader.read(BOOT_ID_PATH)?.trim()?.takeIf(String::isNotEmpty),
            // Supervisor/fan actuation never inherits the display projection's family fallback.
            cpuPackageTempC = strictPackageThermal?.celsius,
            // The strict path is one exact path-backed sensor, never a mean.
            cpuPackageTempZoneCount = strictPackageThermal?.let { 1 },
        )
    }

    /** Exact package-sensor admission used by API-v6 fan capability projection. */
    fun strictOdinPackageThermal(capabilities: HardwareCapabilities): ThermalZoneWinner? =
        capabilities.takeIf { isSnapdragon8EliteFamily(it) }
            ?.let { strictOdinPackageThermalReading(readSupervisorThermalZones(it)) }

    fun fanModeRaw(): String? = Settings.System.getString(context.contentResolver, "fan_mode")

    /**
     * Transaction readback must use the same bounded CPU-topology reader as discovery.
     * RP5 policy0 exposes some cpufreq nodes as system-only, so a direct app-process read
     * can fail after a confirmed PServer write even though the live value is correct.
     */
    fun sysfsIntRaw(path: String): Int? = reader.read(path)?.toIntOrNull()

    /** One shared-map read lets a verification sample assess every Odin CPU policy coherently. */
    fun cpuSharedQosRaw(): String? = reader.read(CPU_QOS_MAX_PATH)

    fun sysfsIntRaw(
        logicalId: String,
        path: String,
        capabilities: HardwareCapabilities,
    ): Int? {
        if (!CpuCeilingTransport.isSharedQos(path)) return sysfsIntRaw(path)
        val policyId = CpuCeilingTransport.policyId(logicalId) ?: return null
        val policy = capabilities.cpuPolicies.firstOrNull { it.id == policyId } ?: return null
        return CpuCeilingTransport.policyValue(reader.read(path), policy.cpuIds)
    }

    /**
     * Nova bring-up 2026-08-22 (late ruling): [CpuWindowAdmissionPolicy.targetAdmission] already
     * builds a message naming both numbers that decided a CPU-window refusal (e.g. "cpu policy
     * $policyId target $target is below settled live floor $min") -- it was constructed and then
     * discarded, never logged. `shell` cannot read `scaling_min_freq` (SELinux) and the daemon would
     * not say, so from outside the device neither number that produces `CPU_TARGET_BELOW_LIVE_FLOOR`
     * could ever be obtained; this is the single thing that was blocking diagnosis of the Nova's
     * apply refusals. Always-on and NOT behind `DIAGNOSTICS_COMPILED` (false in production) --
     * observability only, no control flow, never a secret. Logs every refusal code this admission
     * can produce (`CPU_WINDOW_UNSETTLED`, `CPU_TARGET_BELOW_LIVE_FLOOR`,
     * `CPU_TARGET_ABOVE_LIVE_CEILING`), not only the reported one.
     */
    private fun logCpuWindowRefusal(capabilities: HardwareCapabilities, admission: CpuWindowAdmission) {
        runCatching {
            Log.w(
                CPU_WINDOW_TAG,
                "profile=${capabilities.deviceProfileId} code=${admission.code} " +
                    admission.failures.joinToString("; ") +
                    // Kept reported even though the floor no longer steers derivation
                    // (TuningPlanner.CPU_SETTLED_FLOOR_STEERS_DERIVATION): the next session to look
                    // at this needs the settled floor beside the refusal, not a second expedition.
                    "; settledFloors=${capabilities.cpuSettledFloorsKhz.toSortedMap()}" +
                    // Added 2026-09-19. Diagnosing the Thor took an hour and a read-only
                    // `adb cat` of the QoS node, because the refusal named a ceiling without ever
                    // saying where that ceiling came from or what the device's own maximum was. Both
                    // numbers were already in hand here. A refusal that names one side of an
                    // inequality and not the other cannot be read from outside the device.
                    "; stockCeilings=${capabilities.cpuPolicies.associate { it.id to it.maxKhz }.toSortedMap()}" +
                    "; ceilingTransport=${capabilities.cpuCeilingPath ?: "per-policy scaling_max_freq"}" +
                    (cpuSharedQosRaw()?.let { "; qos=${it.trim()}" }.orEmpty()),
            )
        }
    }

    /**
     * What this capture adopted as stock, whenever that differs from what the nodes read live.
     *
     * Two different stories share this line and the tag makes them distinguishable by their keys.
     * `carried=` is the fix doing its job: a clamp was live at capture time and the boot's earlier
     * baseline was used instead of it. `belowDeviceCeiling=` is the case no baseline can repair --
     * the first capture of this boot already found the hardware clamped, so the remembered "stock"
     * is itself below the device's own maximum and a reboot is the only way back to true stock.
     */
    private fun logStockBaselineAdoption(
        observed: Map<String, Int>,
        baseline: Map<String, Int>,
        stockCeilingsKhz: Map<String, Int>,
    ) {
        runCatching {
            val carried = observed.toSortedMap().mapNotNull { (logicalId, live) ->
                baseline[logicalId]?.takeIf { it != live }?.let { "$logicalId live=$live stock=$it" }
            }
            val poisoned = StockCeilingBaseline.poisonedNodes(baseline, stockCeilingsKhz)
            if (carried.isEmpty() && poisoned.isEmpty()) return
            Log.w(
                STOCK_BASELINE_TAG,
                "carried=${carried.joinToString("; ")} belowDeviceCeiling=${poisoned.joinToString("; ")}",
            )
        }
    }

    /**
     * Capture every topology node a [plan] will mutate. Missing/zero/invalid required state
     * is reported as incomplete — callers must reject before journal or mutation.
     */
    fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
        val failures = mutableListOf<String>()
        val bootIdentity = reader.read(BOOT_ID_PATH)?.trim().orEmpty()
        if (bootIdentity.isBlank()) failures += "boot_identity missing"
        val fingerprint = HardwareFingerprint.create(capabilities)
        if (fingerprint.isBlank()) failures += "hardware fingerprint missing"

        // `scaling_*` is live QoS state, not immutable hardware capability. New
        // transactions own only ceilings. Observe a coherent live window before
        // PREPARED so we can truthfully refuse a target the vendor currently cannot
        // admit without us writing an unowned floor or raising an external ceiling.
        val windows = observeCpuWindows(plan, capabilities)
        if (windows.failures.isNotEmpty()) {
            logCpuWindowRefusal(capabilities, windows)
            return CaptureOutcome(failures = windows.failures, code = windows.code)
        }
        val boundary = observeCpuWindow(plan, capabilities)
        val boundaryAdmission = CpuWindowAdmissionPolicy.atCaptureBoundary(
            settled = windows.values,
            boundary = boundary,
            targetsKhz = plan.cpuMaxKhz,
            operatingPointsKhz = capabilities.cpuPolicies.associate {
                it.id to CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(it)
            },
        )
        if (boundaryAdmission.failures.isNotEmpty()) {
            logCpuWindowRefusal(capabilities, boundaryAdmission)
            return CaptureOutcome(failures = boundaryAdmission.failures, code = boundaryAdmission.code)
        }
        // The boundary read is the freshest window this transaction has, so its clamp is the one
        // that becomes the plan of record. `windows` (the dwell) may have clamped against a
        // slightly older floor -- on a chassis whose floor moves with the charger, freshest wins.
        val admittedCpuMaxKhz = boundaryAdmission.admittedTargetsKhz
            .ifEmpty { windows.admittedTargetsKhz }

        val nodes = mutableListOf<TopologyNodeState>()
        plan.cpuMaxKhz.keys.forEach { policyId ->
            val policy = capabilities.cpuPolicies.firstOrNull { it.id == policyId }
            if (policy == null) {
                failures += "cpu policy $policyId missing from capabilities"
                return@forEach
            }
            val ceilingPath = CpuCeilingTransport.pathFor(policy, capabilities)
            val sharedQos = CpuCeilingTransport.isSharedQos(ceilingPath)
            nodes += captureSysfsNode(
                logicalId = "cpu:$policyId:scaling_max_freq",
                axis = TuningAxis.CPU,
                path = ceilingPath,
                failures = failures,
                expectedValue = if (sharedQos) null else boundaryAdmission.values.getValue(policyId).maxKhz,
                readValue = { resolved ->
                    if (sharedQos) {
                        CpuCeilingTransport.policyValue(reader.read(resolved), policy.cpuIds)
                    } else {
                        reader.read(resolved)?.toIntOrNull()
                    }
                },
            )
        }
        plan.gpuMaxPowerLevel?.let { target ->
            val minNode = captureSysfsNode(
                logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                axis = TuningAxis.GPU,
                path = GPU_MIN_LEVEL,
                failures = failures,
            )
            val slowest = capabilities.gpuFrequenciesKhz.lastIndex
            val capturedMin = minNode.originalValue
            val profileFloor = TuningPlanner.profileSuppliedGpuMinPowerLevel(
                deviceProfileId = capabilities.deviceProfileId,
                profileId = plan.profileId,
                gpuMaxPowerLevel = target,
                slowestLevel = slowest,
            )
            if (slowest < 1 || capturedMin == null || capturedMin !in 0..slowest) {
                failures += "Captured GPU min_pwrlevel $capturedMin outside live range 0..$slowest"
            } else if (capturedMin < target || (profileFloor != null && profileFloor != capturedMin)) {
                // Qualcomm indices are inverted. Own this dynamic node only when it
                // must be widened to admit the requested maximum, or moved to a
                // profile-supplied floor (policy 2026-08-18, Odin balanced only);
                // a no-op write would race the vendor governor and create a false
                // restore obligation, so a floor equal to the captured bound stays
                // unowned. Ownership here and value emission in
                // PServerHardwareCommandPort.plannedMutations consult the same
                // TuningPlanner.profileSuppliedGpuMinPowerLevel so they cannot diverge.
                nodes += minNode
            }
            // ONE ceiling node per apply, chosen by the same pure decision the mutation builder makes
            // (`PServerHardwareCommandPort.plannedMutations`) from the same inputs. Capture and
            // emission disagreeing about WHICH node is owned is the one way this can go wrong
            // silently, so neither side is allowed its own opinion.
            val ceiling = capabilities.gpuCeilingPath
                ?.takeIf { GpuCeilingTransport.targetHzFor(capabilities.gpuFrequenciesKhz, target) != null }
            nodes += if (ceiling != null) {
                captureSysfsNode(
                    logicalId = HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    path = ceiling,
                    failures = failures,
                )
            } else {
                captureSysfsNode(
                    logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    path = GPU_MAX_LEVEL,
                    failures = failures,
                )
            }
        }
        if (plan.fanMode != null) {
            nodes += captureFanNode(
                failures = failures,
                targetFanMode = plan.fanMode,
                capabilities = capabilities,
            )
        }
        // After fan, matching `SnapshotIntegrity.expectedTopology`'s order (the topology identity is
        // order-sensitive). The apply ORDER is separate: vendor mode, fan, then compute (2026-10-13).
        if (plan.vendorMode != null) {
            val vendorNode = captureVendorModeNode(failures = failures, capabilities = capabilities)
            nodes += vendorNode
            // PRODUCTION-visible, one line per capture: the 2026-10-13 Thor drive could not say whose
            // mode a restore was putting back because nothing logged the origin. Hardware numbers only.
            runCatching {
                Log.i(
                    VENDOR_MODE_TAG,
                    "VENDOR_MODE_CAPTURE origin=${vendorNode.originalValue ?: "none"} applied=${plan.vendorMode} " +
                        "live=${vendorModeRaw()?.trim() ?: "null"} fan=${nodes.firstOrNull { it.axis == TuningAxis.FAN }?.originalValue ?: "none"} profile=${plan.profileId}",
                )
            }
        }

        // Metadata/GPU/fan capture itself takes time. Re-read both live CPU bounds at
        // the final pre-PREPARED edge so no floor/ceiling movement can slip between
        // admission and durable snapshot creation.
        val finalBoundary = observeCpuWindow(plan, capabilities)
        val finalAdmission = CpuWindowAdmissionPolicy.atCaptureBoundary(
            settled = boundaryAdmission.values,
            boundary = finalBoundary,
            targetsKhz = plan.cpuMaxKhz,
            operatingPointsKhz = capabilities.cpuPolicies.associate {
                it.id to CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(it)
            },
        )
        if (finalAdmission.failures.isNotEmpty()) {
            logCpuWindowRefusal(capabilities, finalAdmission)
            return CaptureOutcome(failures = finalAdmission.failures, code = finalAdmission.code)
        }
        nodes.filter { node ->
            node.axis == TuningAxis.CPU && CpuCeilingTransport.isSharedQos(node.canonicalPath)
        }.forEach { node ->
            val finalValue = sysfsIntRaw(node.logicalId, node.canonicalPath, capabilities)
            if (finalValue != node.originalValue) {
                failures += "${node.logicalId} changed after QoS capture: expected ${node.originalValue} got $finalValue"
            }
        }

        if (failures.isNotEmpty()) {
            return CaptureOutcome(
                failures = failures,
                code = if (failures.any { "changed after settled observation" in it }) {
                    CPU_WINDOW_UNSETTLED
                } else {
                    null
                },
            )
        }
        // The restore point is what the node read before Mimir first clamped it in THIS boot, not
        // what it happens to read now. See [StockCeilingBaseline] for the device evidence: a re-arm
        // into an un-restored clamp captured Quiet's ceilings as stock, and from then on "Stock"
        // meant Quiet and no profile above it could ever be admitted again.
        //
        // Substituted HERE, after every check above, on purpose. Those checks ask whether the window
        // moved under this transaction and must keep comparing the snapshot against what is live;
        // that is a different question from what stock was, and answering both with one number is
        // what let the clamp become its own justification.
        val observedVolatile = nodes
            .filter { StockCeilingBaseline.governs(it.axis) }
            .mapNotNull { node -> node.originalValue?.let { node.logicalId to it } }
            .toMap()
        val baseline = stockCeilingBaselines.remember(bootIdentity, observedVolatile).values
        logStockBaselineAdoption(
            observed = observedVolatile,
            baseline = baseline,
            stockCeilingsKhz = nodes.mapNotNull { node ->
                CpuCeilingTransport.policyId(node.logicalId)
                    ?.let { id -> capabilities.cpuPolicies.firstOrNull { it.id == id } }
                    ?.let { node.logicalId to it.maxKhz }
            }.toMap(),
        )
        return CaptureOutcome(
            snapshot = HardwareSnapshot(
                schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                hardwareFingerprint = fingerprint,
                bootIdentity = bootIdentity,
                nodes = nodes.map { node ->
                    val remembered = baseline[node.logicalId]
                    if (StockCeilingBaseline.governs(node.axis) && remembered != null) {
                        node.copy(originalValue = remembered)
                    } else {
                        node
                    }
                },
            ),
            // Freshest window wins: the final boundary read is the last thing this transaction saw
            // before writing, and on a chassis whose floor moves with the charger that matters.
            admittedCpuMaxKhz = finalAdmission.admittedTargetsKhz
                .ifEmpty { admittedCpuMaxKhz },
        )
    }

    /**
     * Re-admit the live CPU window after a vendor-coupled cooling mutation and just
     * before compute mutation. This is observation-only: it never captures or owns a
     * floor, and callers must restore any completed cooling mutation on failure.
     */
    fun admitCpuTargetsAfterCooling(
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
    ): CpuWindowAdmission {
        if (plan.cpuMaxKhz.isEmpty()) return CpuWindowAdmission()
        val settled = observeCpuWindows(plan, capabilities)
        if (settled.failures.isNotEmpty()) return settled
        return CpuWindowAdmissionPolicy.atCaptureBoundary(
            settled = settled.values,
            boundary = observeCpuWindow(plan, capabilities),
            targetsKhz = plan.cpuMaxKhz,
            operatingPointsKhz = capabilities.cpuPolicies.associate {
                it.id to CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(it)
            },
        )
    }

    /** Legacy helper retained for telemetry-adjacent callers; not transaction authority. */
    fun capture(capabilities: HardwareCapabilities): HardwareSnapshot {
        val stockPlan = TuningPlan(
            profileId = "stock",
            packageName = "legacy.capture",
            cpuMaxKhz = capabilities.cpuPolicies.associate { it.id to it.maxKhz },
            gpuMaxPowerLevel = if (capabilities.gpuNodeAvailable) 0 else null,
            fanMode = if (TuningAxis.FAN in capabilities.supportedAxes) 0 else null,
            calibrated = false,
        )
        return captureForPlan(stockPlan, capabilities).snapshot
            ?: HardwareSnapshot(
                schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                hardwareFingerprint = HardwareFingerprint.create(capabilities),
                bootIdentity = reader.read(BOOT_ID_PATH)?.trim().orEmpty(),
                nodes = emptyList(),
            )
    }

    private fun captureSysfsNode(
        logicalId: String,
        axis: TuningAxis,
        path: String,
        failures: MutableList<String>,
        expectedValue: Int? = null,
        readValue: (String) -> Int? = { resolved -> reader.read(resolved)?.toIntOrNull() },
    ): TopologyNodeState {
        val canonical = SysfsPath.canonicalize(path)
        if (canonical == null) {
            failures += "$logicalId noncanonical path $path"
        }
        val resolved = canonical ?: path
        val value = readValue(resolved)
        if (expectedValue != null && value != expectedValue) {
            failures += "$logicalId changed after settled observation: expected $expectedValue got $value"
        }
        val invalid = when (axis) {
            // CPU clocks must be positive; GPU power-level indices may legitimately be 0.
            TuningAxis.CPU -> value == null || value <= 0
            TuningAxis.GPU -> value == null || value < 0
            TuningAxis.FAN -> value == null
            // Not a sysfs node, so never reached through here; [captureVendorModeNode] owns it.
            // Fail closed rather than judge a value this function cannot read.
            TuningAxis.VENDOR_MODE -> true
        }
        if (invalid) {
            failures += "$logicalId missing/invalid value at $resolved"
        }
        val meta = posixMeta(resolved)
        if (meta.mode.isNullOrBlank()) {
            failures += "$logicalId missing mode at $resolved"
        }
        if (meta.uid == null) {
            failures += "$logicalId missing UID at $resolved"
        }
        if (meta.gid == null) {
            failures += "$logicalId missing GID at $resolved"
        }
        return TopologyNodeState(
            logicalId = logicalId,
            axis = axis,
            canonicalPath = resolved,
            originalValue = value,
            originalMode = meta.mode,
            originalUid = meta.uid,
            originalGid = meta.gid,
            present = value != null && meta.mode != null && meta.uid != null && meta.gid != null,
        )
    }

    private fun captureFanNode(
        failures: MutableList<String>,
        targetFanMode: Int,
        capabilities: HardwareCapabilities,
    ): TopologyNodeState {
        val raw = fanModeRaw()
        val present = raw != null
        val value = raw?.toIntOrNull()
        if (present && value == null) {
            failures += "fan_mode present but not an integer"
        }
        if (present && value != null && !TuningPlanner.isCapturedVendorFanMode(value)) {
            failures += "fan_mode $value outside vendor range"
        }
        if (targetFanMode == TuningPlanner.FAN_MANUAL) {
            // 2026-08-23: the exact-Odin device-id failure that stood here is retired (policy
            // 2026-08-22, family not device). The three checks that remain are the ones that
            // actually protect the capture -- a vendor origin to restore to, no other controller
            // already holding mode 6, and live period/duty nodes in range -- and the last of those
            // resolves through HandheldDeviceProfiles, which only yields nodes for a device whose
            // gpio5_pwm2 paths exist. A chassis with no PWM still fails here, on evidence.
            if (!present) {
                failures += "manual fan mode requires a captured vendor origin"
            }
            if (value == TuningPlanner.FAN_MANUAL) {
                failures += "manual fan mode already owned by another controller"
            }
            val fanNodes = HandheldDeviceProfiles.resolve(
                manufacturer = capabilities.manufacturer,
                model = capabilities.model,
                socModel = capabilities.socModel,
                reader = reader,
            ).fanNodes
            val period = fanNodes?.periodPath?.let { reader.read(it)?.toIntOrNull() }
            val duty = fanNodes?.dutyPath?.let { reader.read(it)?.toIntOrNull() }
            if (period == null || period !in FanPwmBounds.PERIOD_RANGE) {
                failures += "manual fan period missing or outside bounds"
            }
            if (duty == null || period == null || duty !in 0..period) {
                failures += "manual fan duty missing or outside live period"
            }
        }
        return TopologyNodeState(
            logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
            axis = TuningAxis.FAN,
            canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            originalValue = value,
            originalMode = null,
            originalUid = null,
            originalGid = null,
            present = present,
        )
    }

    /** The direct `Settings.System` read: the authoritative, immediate view of `performance_mode`. */
    fun vendorModeRaw(): String? = vendorModeSettingReader()

    /** The direct `Settings.System` read of the AYN fan-and-mode coupling flag; read only, see [VendorFanCouplingSettle]. */
    fun vendorFanCouplingRaw(): String? = vendorFanCouplingSettingReader()

    /**
     * `performance_mode` as a KNOWN mode, or null when it is unreadable, not an integer, or outside
     * [TuningPlanner.KNOWN_VENDOR_MODES]. The one definition shared by presence and capture, so
     * the axis is never offered for a value the capture would then refuse.
     */
    private fun readVendorModeLive(): Int? =
        vendorModeRaw()?.trim()?.toIntOrNull()?.takeIf { it in TuningPlanner.KNOWN_VENDOR_MODES }

    /**
     * The vendor-mode snapshot node, whose `originalValue` is the HONEST ORIGIN -- the player's mode
     * before Mimir first changed it -- and NOT the live read. A re-arm after a frozen daemon would
     * otherwise read Mimir's own `2` as "stock" and restore it forever (the 2026-09-19 trap, for a
     * `settings` key that survives a reboot). [VendorModeOriginPolicy.resolveAtCapture] decides:
     * a stored record whose `applied` still equals the live value keeps its origin; any other live
     * value means the player moved it, and the live read becomes the origin.
     *
     * Read-only: nothing is saved or cleared here. A capture that never reaches an apply must not
     * rewrite the record; [PServerHardwareCommandPort] persists it before the first write.
     */
    private fun captureVendorModeNode(
        failures: MutableList<String>,
        capabilities: HardwareCapabilities,
    ): TopologyNodeState {
        if (TuningAxis.VENDOR_MODE !in capabilities.supportedAxes) {
            failures += "vendor mode planned without an admitted vendor-mode axis"
        }
        val raw = vendorModeRaw()
        val live = raw?.trim()?.toIntOrNull()
        if (live == null) {
            failures += "performance_mode unreadable or not an integer"
        } else if (live !in TuningPlanner.KNOWN_VENDOR_MODES) {
            failures += "performance_mode $live outside known modes"
        }
        val origin = if (live != null && live in TuningPlanner.KNOWN_VENDOR_MODES) {
            val load = vendorModeOriginStore.load()
            if (load is VendorModeOriginLoad.Corrupt) {
                // Treated as no record by the policy; say so, because it silently degrades to the
                // pre-record behaviour for this transaction.
                runCatching { Log.w(VENDOR_MODE_TAG, "origin record unreadable; capturing the live mode as origin") }
            }
            val resolution = VendorModeOriginPolicy.resolveAtCapture(load.recordOrNull, live)
            resolution.retired?.let { retired ->
                runCatching {
                    Log.i(
                        VENDOR_MODE_TAG,
                        "origin record retired: live=$live applied=${retired.applied} origin=${retired.origin}; " +
                            "the player moved the mode, so the live value is the origin",
                    )
                }
            }
            resolution.origin
        } else {
            null
        }
        return TopologyNodeState(
            logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
            axis = TuningAxis.VENDOR_MODE,
            canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
            originalValue = origin,
            originalMode = null,
            originalUid = null,
            originalGid = null,
            present = origin != null,
        )
    }

    private data class PosixMeta(val mode: String?, val uid: Int?, val gid: Int?)

    private fun posixMeta(path: String): PosixMeta = runCatching {
        val stat = Os.lstat(path)
        val mode = Integer.toOctalString(stat.st_mode and 0x1FF)
        PosixMeta(mode = mode, uid = stat.st_uid, gid = stat.st_gid)
    }.getOrElse { PosixMeta(mode = null, uid = null, gid = null) }

    private var certificationStatusProvider: CertificationStore = CertificationStore.NoOp

    fun attachCertificationStore(store: CertificationStore) {
        certificationStatusProvider = store
    }

    private fun detectCpuPolicies(): List<CpuPolicy> = reader.directories(CPU_ROOT, "policy").mapNotNull { dir ->
        val id = dir.name.removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
        val frequencies = readFrequencyList("${dir.path}/scaling_available_frequencies")
            .map { it.toInt() }
        val max = reader.read("${dir.path}/cpuinfo_max_freq")?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: frequencies.lastOrNull()
            ?: return@mapNotNull null
        // cpuinfo_min_freq is immutable policy capability. scaling_min_freq is a
        // mutable launch/QoS floor and must never feed planning or topology identity.
        val min = reader.read("${dir.path}/cpuinfo_min_freq")?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: frequencies.firstOrNull()
            ?: return@mapNotNull null
        if (min > max) return@mapNotNull null
        CpuPolicy(
            id = id,
            path = dir.path,
            cpuIds = parseInts(reader.read("${dir.path}/affected_cpus")),
            availableFrequenciesKhz = frequencies.ifEmpty { listOf(min, max).filter { it > 0 }.distinct().sorted() },
            minKhz = min,
            maxKhz = max,
            currentKhz = reader.read("${dir.path}/scaling_cur_freq")?.toIntOrNull() ?: 0,
        )
    }

    private fun readClassifiedThermalZones(): List<ClassifiedThermalZone> {
        return sampleThermalZones(thermalZoneTopology())
    }

    /** Monitor only needs classified CPU/GPU safety families and the Odin package family. */
    private fun readSupervisorThermalZones(
        capabilities: HardwareCapabilities,
    ): List<ClassifiedThermalZone> {
        val topology = thermalZoneTopology()
        val selected = if (capabilities.deviceProfileId == CpuCeilingTransport.ODIN_3_PROFILE) {
            topology.filter { zone ->
                val type = zone.type.lowercase()
                "cpu" in type || "gpu" in type || "kgsl" in type || "gfx" in type
            }
        } else {
            topology
        }
        return sampleThermalZones(selected)
    }

    private fun thermalZoneTopology(): List<ThermalZoneTopology> =
        thermalZoneTopologyCache ?: synchronized(this) {
            thermalZoneTopologyCache ?: reader.directories(THERMAL_ROOT, "thermal_zone")
                .mapNotNull { zone ->
                    reader.read("${zone.path}/type")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { type -> ThermalZoneTopology(type = type, path = zone.path) }
                }
                .also { discovered ->
                    // An empty read may be a transient permission/mount failure; retry later.
                    if (discovered.isNotEmpty()) thermalZoneTopologyCache = discovered
                }
        }

    private fun sampleThermalZones(
        topology: List<ThermalZoneTopology>,
    ): List<ClassifiedThermalZone> = topology.map { zone ->
            ClassifiedThermalZone(
                type = zone.type,
                rawTemperature = reader.read("${zone.path}/temp")?.toLongOrNull(),
                path = zone.path,
            )
        }

    private data class ThermalWitnesses(
        val cpuWinner: ThermalZoneWinner?,
        val gpuWinner: ThermalZoneWinner?,
        val inventory: CalibrationThermalInventory,
    )

    /** Full provenance freezes inventory; the winner remains the deterministic hottest family member. */
    private fun thermalWitnesses(
        zones: List<ClassifiedThermalZone> = readClassifiedThermalZones(),
    ): ThermalWitnesses {
        val (cpuWinner, gpuWinner) = classifiedThermalZoneWinners(zones)
        fun identities(predicate: (String) -> Boolean): List<ThermalZoneIdentity> = zones.mapNotNull { zone ->
            val family = thermalZoneFamily(zone.type)
            zone.takeIf { it.path.isNotBlank() && family.isNotBlank() && predicate(zone.type.lowercase()) }
                ?.let { ThermalZoneIdentity(family, it.type, it.path) }
        }.distinct().sortedWith(compareBy(ThermalZoneIdentity::family, ThermalZoneIdentity::path))
        return ThermalWitnesses(
            cpuWinner = cpuWinner,
            gpuWinner = gpuWinner,
            inventory = CalibrationThermalInventory(
                cpu = identities { "cpu" in it },
                gpu = identities { "gpu" in it || "kgsl" in it || "gfx" in it },
            ),
        )
    }

    private fun systemProperty(name: String): String? = runCatching {
        ProcessBuilder("/system/bin/getprop", name).start().inputStream.bufferedReader().use { it.readText().trim() }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun readFrequencyList(path: String): List<Long> = reader.read(path).orEmpty()
        .split(Regex("\\s+"))
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0 }
        .distinct()
        .sorted()

    /**
     * Require a bounded full dwell and two equal, internally coherent final samples
     * of every planned policy. This lets an Activity-launch boost settle instead of
     * accepting/rejecting a transient 75ms plateau. Values are observations only and
     * are deliberately not journaled.
     */
    private fun observeCpuWindows(
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
    ): CpuWindowAdmission {
        // Timed because the cost of this loop was twice attributed by adding up constants and twice
        // got wrong -- once to a settlement loop that only runs in the rollback drill. Always-on and
        // NOT behind DIAGNOSTICS_COMPILED, the same posture as logCpuWindowRefusal: a switch that
        // feels slow should be able to say where the time went without a special build.
        // Observability only, no control flow, and a hardware timing is never a secret.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val samples = buildList {
            repeat(CpuWindowAdmissionPolicy.SAMPLE_COUNT) { index ->
                add(observeCpuWindow(plan, capabilities))
                if (index < CpuWindowAdmissionPolicy.SAMPLE_COUNT - 1) {
                    sleeper(CPU_WINDOW_ADMISSION_SETTLE_MS)
                }
            }
        }
        val admission = CpuWindowAdmissionPolicy.afterDwell(
            samples = samples,
            targetsKhz = plan.cpuMaxKhz,
            operatingPointsKhz = capabilities.cpuPolicies.associate {
                it.id to CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(it)
            },
        )
        runCatching {
            android.util.Log.i(
                CPU_WINDOW_TAG,
                "dwell samples=${samples.size} elapsedMs=" +
                    (android.os.SystemClock.elapsedRealtime() - startedAt) +
                    " code=" + (admission.code ?: "ok"),
            )
        }
        return admission
    }

    private fun observeCpuWindow(
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
    ): Map<Int, CpuLiveWindowSample> = buildMap {
        plan.cpuMaxKhz.toSortedMap().forEach { (policyId, _) ->
            val policy = capabilities.cpuPolicies.firstOrNull { it.id == policyId }
            if (policy != null) {
                put(
                    policyId,
                    CpuLiveWindowSample(
                        minKhz = reader.read("${policy.path}/scaling_min_freq")?.toIntOrNull(),
                        maxKhz = reader.read("${policy.path}/scaling_max_freq")?.toIntOrNull(),
                    ),
                )
            }
        }
    }

    private fun parseInts(raw: String?): List<Int> = raw.orEmpty()
        .split(Regex("\\s+"))
        .mapNotNull { it.toIntOrNull() }
        .distinct()
        .sorted()

    private fun readInt(path: String): Int = reader.read(path)?.toIntOrNull() ?: 0

    private fun readNullableInt(path: String): Int? = reader.read(path)?.toIntOrNull()

    @Suppress("DEPRECATION")
    private fun externalPowerState(): ExternalPowerState {
        val plugged = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, Int.MIN_VALUE)
        }.getOrNull() ?: return ExternalPowerState.UNKNOWN
        return when (plugged) {
            0 -> ExternalPowerState.OFFLINE
            BatteryManager.BATTERY_PLUGGED_USB -> ExternalPowerState.USB
            BatteryManager.BATTERY_PLUGGED_AC -> ExternalPowerState.AC
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> ExternalPowerState.WIRELESS
            else -> ExternalPowerState.UNKNOWN
        }
    }

    /**
     * Resolved once and then sticky. With a helper present the privileged route is the ONLY route (these nodes are
     * denied to this app's domain, and a direct read is audited on every call, even where it succeeds); the direct read
     * is for a device with no helper at all. The helper read has a deadline ([BoundedRead]), the value is reused for 5 s,
     * and a source nothing can read is not probed again for [GpuUtilizationReader.UNAVAILABLE_RETRY_MS].
     */
    private val gpuUtilization = GpuUtilizationReader(
        readPrivileged = BoundedRead(reader::readPrivileged)::read,
        readDirect = reader::readDirect,
        helperPresent = reader::helperPresent,
        nowMs = SystemClock::elapsedRealtime,
    )

    private fun gpuBusyPercent(): Int? {
        val reading = gpuUtilization.read()
        val selectedSource = reading.source?.let { "$it route=${reading.route}" }
        if (!gpuSourceInitialized || selectedSource != lastGpuSource) {
            gpuSourceInitialized = true
            lastGpuSource = selectedSource
            Log.i(
                GPU_TELEMETRY_TAG,
                "source=${selectedSource ?: "unavailable"} uid=${Process.myUid()}",
            )
        }
        return reading.percent
    }

    companion object {
        // Odin 3 Game Assistant reads thermal_zone23; the physical device identifies that
        // zone as exact type cpuss-0-1. Resolve by type rather than the unstable zone number,
        // then fall back to the cpuss-0 package family. Hottest classified CPU/GPU zones
        // remain separate Guardian safety witnesses.
        private val ODIN_3_PACKAGE_THERMAL_PREFIXES = listOf(ODIN_PACKAGE_THERMAL_TYPE, "cpuss-0")
        /** Probe-identity observability; see the Log.i at the end of [probe]. */
        private const val IDENTITY_TAG = "MimirTweakerIdentity"
        /** CPU-window admission refusal observability; see [logCpuWindowRefusal]. */
        private const val CPU_WINDOW_TAG = "MimirCpuWindowAdmission"
        private const val STOCK_BASELINE_TAG = "MimirStockBaseline"
        private const val VENDOR_MODE_TAG = "MimirVendorMode"
        /** GPU utilization source transitions; emitted once per source edge, including unavailable. */
        private const val GPU_TELEMETRY_TAG = "MimirGpuTelemetry"

        /**
         * GPU source observability is edge-only, and the edge is PROCESS-wide. The service is destroyed when its last client
         * unbinds and a fresh [HardwareProbe] is built each time it is created, so a per-probe edge logged once per bind: 22
         * identical lines in 30 s on the Nova while Utility was open (21:48:59-21:49:30).
         */
        @Volatile
        private var gpuSourceInitialized = false

        @Volatile
        private var lastGpuSource: String? = null
        const val CPU_ROOT = "/sys/devices/system/cpu/cpufreq"
        const val CPU_QOS_MAX_PATH = SHARED_CPU_QOS_CEILING_PATH
        const val GPU_ROOT = "/sys/class/kgsl/kgsl-3d0"
        const val GPU_FREQUENCIES = "$GPU_ROOT/gpu_available_frequencies"
        const val GPU_DEVFREQ_FREQUENCIES = "$GPU_ROOT/devfreq/available_frequencies"
        const val GPU_CLOCK = "$GPU_ROOT/gpuclk"
        const val GPU_MIN_LEVEL = "$GPU_ROOT/min_pwrlevel"
        const val GPU_MAX_LEVEL = "$GPU_ROOT/max_pwrlevel"

        /**
         * The devfreq POLICY cap, in Hz. The GPU's answer to [CPU_QOS_MAX_PATH].
         *
         * `max_pwrlevel` is to the GPU what `scaling_max_freq` was to the CPU: a node this chassis's
         * vendor stack reasserts underneath us. Measured on the Nova 2026-08-25, Tweaker
         * `568944db…`, all three axes polled together at 5 Hz:
         *
         * ```
         * 18:47:18.510   cpu PINNED(1017600)   gpu_max 0
         * 18:47:19.273   cpu PINNED(1017600)   gpu_max 3     <- our write lands
         * 18:47:23.289   cpu PINNED(1017600)   gpu_max 0     <- reverted ALONE
         * 18:47:24.036   SUPERVISOR restore reason=TARGET_CONTRACT_DRIFT_GPU_MAX expected=3 observed=0
         * 18:47:24.800   cpu unpinned                        <- our own restore, 1.5s LATER
         * ```
         *
         * The CPU ceiling holds across the GPU flip, so nothing of ours wrote it -- a Mimir restore
         * moves every axis at once, and ours arrives a second and a half afterwards, as a consequence.
         * The GPU node was also the only one of the four with a fresh mtime; this one still reads
         * 1970, i.e. nothing on the device has ever written it.
         */
        const val GPU_DEVFREQ_MAX_FREQ = "$GPU_ROOT/devfreq/max_freq"
        const val FAN_DUTY = "/sys/class/gpio5_pwm2/duty"
        const val FAN_PERIOD = "/sys/class/gpio5_pwm2/period"
        const val FAN_SPEED = "/sys/class/gpio5_pwm2/speed"
        const val THERMAL_ROOT = "/sys/class/thermal"
        const val BATTERY_ROOT = "/sys/class/power_supply/battery"
        const val BATTERY_CAPACITY = "$BATTERY_ROOT/capacity"
        const val BATTERY_CURRENT_UA = "$BATTERY_ROOT/current_now"
        const val BATTERY_VOLTAGE_UV = "$BATTERY_ROOT/voltage_now"
        const val BATTERY_TEMP = "$BATTERY_ROOT/temp"
        const val BATTERY_STATUS = "$BATTERY_ROOT/status"
        const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        private const val CPU_WINDOW_SETTLE_MS = 75L

        /**
         * The ADMISSION dwell's own interval. **Back to 75 ms**, with
         * [CpuWindowAdmissionPolicy.SAMPLE_COUNT] at three: 150 ms of observation, down from 2 s.
         *
         * It was raised to 200 ms so eleven samples would span 2 s and outlast a vendor perf-HAL
         * oscillation whose phases run about a second on the Thor — the whole dwell could otherwise
         * sit inside one phase and see only half the band, which defeated the point of aggregating
         * over it . That reasoning was load-bearing while the band
         * steered the target. It no longer does, on either axis, since the two 2026-09-21 rulings;
         * see [CpuWindowAdmissionPolicy.SAMPLE_COUNT] for what still reads this dwell.
         *
         * Still a separate constant from [CPU_WINDOW_SETTLE_MS] rather than a reference to it, even
         * though the two now hold the same number. They answer different questions: this paces the
         * ADMISSION dwell, that paces the applied-plan VERIFICATION loop, whose cadence is pinned by
         * its own tests. Collapsing them would let a change to either silently move the other.
         */
        private const val CPU_WINDOW_ADMISSION_SETTLE_MS = 75L

        /**
         * Rounds for [readSettledCpuFloorsKhz]. **Three, not
         * [CpuWindowAdmissionPolicy.SAMPLE_COUNT]'s eleven, and the difference was measured, not
         * chosen.** The first cut of this reused the admission dwell's sample count on the reasoning
         * that one question deserves one discipline. On the reference Nova that cost eleven sequential
         * reads per policy across three policies -- about 2.25s -- inside `discoverStaticTopology`,
         * which sits on the capability probe the frontend bridge WAITS on. The bridge gave up:
         * `availability=UNAVAILABLE bound=false`, `rearm ... exit=no_capability_truth`, and the
         * device fell back to Stock-only with the daemon itself perfectly healthy beside it.
         *
         * Eleven samples were sized for a one-shot pre-apply dwell where nothing is waiting. This
         * read is on a handshake path, so it is sized for a handshake: three round-robin rounds,
         * 150ms total for any cluster count. The authoritative settle has not moved -- it is still
         * [CpuWindowAdmissionPolicy]'s full dwell immediately before the write, which is where a
         * floor that moves must be caught. This one only has to be good enough to steer derivation.
         */
        private const val CPU_FLOOR_SETTLE_ROUNDS = 3
        const val CPU_WINDOW_UNSETTLED = "CPU_WINDOW_UNSETTLED"
        const val CPU_TARGET_BELOW_LIVE_FLOOR = "CPU_TARGET_BELOW_LIVE_FLOOR"

        /**
         * Observability, never a failure: the target was raised to the policy's live floor and the
         * apply proceeded. Policy 2026-08-23.
         */

        /**
         * Observability, never a failure: the target was not one of the policy's published operating
         * points and was snapped down onto the nearest one below it. the OPP
         * table decides what may be written, not the live ceiling, which retired
         * `CPU_TARGET_ABOVE_LIVE_CEILING` along with the self-locking refusal it named.
         */
        const val CPU_TARGET_SNAPPED_TO_OPERATING_POINT = "CPU_TARGET_SNAPPED_TO_OPERATING_POINT"
    }
}

/**
 * WHICH node actually governs the GPU ceiling on this chassis -- the GPU's [CpuCeilingTransport].
 *
 * The CPU learned this lesson first: writing the node the vendor's perf stack also writes is a race
 * we lose, quietly, and the only symptom is a profile that will not stay on. The GPU's version of
 * that node is `max_pwrlevel`; its version of the composed one is the devfreq policy cap.
 *
 * **Nothing here is relaxed and nothing is guessed.** The devfreq cap is chosen ONLY when the index
 * it must translate can be translated totally and unambiguously:
 *
 *  - the node exists;
 *  - the live frequency table is non-trivial;
 *  - the requested level addresses a real row.
 *
 * A `num_pwrlevels` cross-check USED to sit here and was removed, not weakened: the decision is made
 * at FOUR sites (capture, emission, apply-time verify, and the expected-topology the capture is checked
 * against) and only two of them can read that node. An input one site has and another does not is a
 * divergence waiting to happen, and a divergence here means the stock value restored on teardown is
 * for a node nobody wrote. Every site now decides from the frequency table alone, which comes from
 * capabilities and is identical everywhere.
 *
 * Any of those failing returns null and the caller writes `max_pwrlevel` exactly as before, so a
 * chassis this cannot serve is byte-identical to today. And a chassis where the node exists but does
 * not GOVERN cannot fail silently either: the apply verifies by reading back through the SELECTED
 * transport, so an inert cap fails verification and refuses honestly rather than reporting a ceiling
 * it never took.
 */
internal object GpuCeilingTransport {

    /**
     * Qualcomm power levels are INVERTED -- index 0 is the fastest -- while the probe hands us
     * frequencies sorted ascending. Level `i` is therefore the `i`-th row from the TOP.
     *
     * Getting this backwards would not throw: it would cap the GPU at 124.8 MHz where 680 was asked
     * for, or the reverse, and the only evidence would be the feel of the device. Hence the totality
     * requirement above rather than a `getOrNull`.
     */
    fun targetHzFor(gpuFrequenciesKhz: List<Int>, powerLevel: Int): Long? {
        if (gpuFrequenciesKhz.size < 2) return null
        val row = gpuFrequenciesKhz.lastIndex - powerLevel
        if (row !in gpuFrequenciesKhz.indices) return null
        return gpuFrequenciesKhz[row].toLong() * 1_000L
    }

    fun selectedPath(
        gpuFrequenciesKhz: List<Int>,
        powerLevel: Int,
        devfreqMaxExists: Boolean,
    ): String? {
        if (!devfreqMaxExists) return null
        targetHzFor(gpuFrequenciesKhz, powerLevel) ?: return null
        return HardwareProbe.GPU_DEVFREQ_MAX_FREQ
    }
}
