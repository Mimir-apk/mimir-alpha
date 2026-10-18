package com.gruning.mimir.rewrite

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Requesting a frequency ceiling, and taking it back.
 *
 * **THE AXIS IS `msm_performance`, NOT `scaling_max_freq`, AND THAT IS THE WHOLE ARCHITECTURE.**
 * `/sys/devices/system/cpu/cpufreq/policyN/scaling_max_freq` is the node everyone reaches for and it
 * RACES the vendor performance HAL: this project's own device work measured **21 HAL restores in four
 * minutes** writing that node on a Retroid Nova, against **zero** on the QoS node with identical code
 * (`mimir-tweaker/`, 2026-08-25). `/sys/kernel/msm_performance/parameters/cpu_max_freq` is a REQUEST
 * the HAL composes with instead of a value it owns, so a cap written there is a cap that stays.
 *
 * **The two nodes answer different questions and this device proves it.** On the Thor at rest,
 * `policy0/scaling_max_freq` reads 1 555 200 while the QoS node requests 2 016 000 for the same CPUs.
 * Neither is wrong: the QoS node says what has been REQUESTED, `scaling_max_freq` says what is IN
 * FORCE after thermal and the HAL have had their say. So this file writes one and reads back the
 * other, on purpose, and a surface that showed the requested number as though it were the live one
 * would be making exactly the claim §4 bans.
 *
 * **RESTORE IS A CAPTURE, NOT A CONSTANT — corrected on the device.** The write-path review argued
 * stock is `INT_MAX` on every CPU, from three Odin captures, and that withdrawal could therefore be a
 * constant needing no journal. The Thor says otherwise:
 *
 * ```
 * 0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2147483647
 * ```
 *
 * Six of eight CPUs hold their cluster's hardware ceiling. Behaviourally that is still no cap — a
 * ceiling requested AT the ceiling restricts nothing — so the review's reading was right and its
 * premise was wrong, which is the most dangerous combination there is. Writing `INT_MAX` everywhere
 * would have left six CPUs holding a value the device did not boot with.
 *
 * **The "only init writes this axis" premise is RETIRED — it was measured false on 2026-09-01.** It
 * rested on the node's mtime matching boot and not moving six hours later. That observation was real
 * and the conclusion drawn from it was not: instrumenting the daemon's own privileged channel caught
 * `MimirTweakerService.selectSystemProfileOnWorker` writing this exact node — 21 commands, the whole
 * eight-CPU row plus the GPU — during Mimir's own startup. The mtime test could not see it because
 * nobody had applied a profile in the window it covered.
 *
 * So the Tweaker daemon IS a third writer, and "a lost capture is one reboot, never a stuck handheld"
 * no longer follows: a profile apply rewrites the row while Mimir believes it holds a ceiling. That
 * conflict — two subsystems owning one sysfs node with no agreement about which wins — is the basis of this routing constraint.
 *
 * The daemon owns the clock node whenever present; the Tuner routes through it and writes directly
 * only on devices without a daemon. Every entry point begins by asking
 * [MimirTunerDaemonRoute] who may write — see `MimirTunerRoute.kt` for the table, which is a pure
 * function of two facts so that no call site can re-derive it differently. The direct path is unchanged
 * on a device with no daemon: that is not a claim, it is what `MimirTunerWriteTest` pins.
 *
 * The absence of a journal, a signed envelope and an out-of-process guardian is a separate question,
 * and on the daemon route it is answered by the daemon's own store rather than by anything here.
 */
object MimirTunerWrite {

    private const val TAG = "MimirTuner"

    /** The QoS request node: one `cpuId:kHz` pair per CPU, space separated. */
    internal const val CpuQosNode = "/sys/kernel/msm_performance/parameters/cpu_max_freq"

    /** The Adreno's bound nodes. Named here because withdrawal needs them without a policy in hand. */
    internal const val GpuRoot = "/sys/class/kgsl/kgsl-3d0"

    /**
     * The kernel's own name for this boot, so a record cannot be adopted across a reboot.
     *
     * Read through [MimirTunerNodes] rather than with a direct `File`, so the identity is injectable
     * in a test the same way every other read on this axis already is.
     */
    internal const val BootIdNode = "/proc/sys/kernel/random/boot_id"

    /**
     * What the device was holding before Mimir touched it, captured once per process.
     *
     * **Captured lazily, at the FIRST write, and never refreshed.** Capturing at construction would
     * snapshot whatever the HAL happened to be doing at process start; capturing per-write would let
     * the second write record the first write's own cap as "stock" and make withdrawal a no-op. Once,
     * before anything of ours is in force, is the only moment the value means what the name says.
     */
    @Volatile
    private var capturedCpuRow: String? = null

    /**
     * Exactly what Mimir asked each CPU for, so withdrawal can tell OUR value from somebody else's.
     *
     * **The node has another writer.** It was recorded
     * as settled on 2026-08-31 on the grounds that the node's mtime matched boot and had not moved in
     * six hours — then CPU 7 was observed at `INT_MAX` at 16:44 and at `2956800` at 17:14 with no Mimir
     * write in between. sysfs mtime does not reliably track writes and should never have been treated
     * as proof.
     *
     * Without this map, a restore is a blind stamp: if the other writer moves a CPU between our capture
     * and our withdraw, writing the whole captured row back would overwrite their current value with
     * our stale one — Mimir silently undoing a change it never made and cannot see. With it, withdrawal
     * restores only the CPUs still holding what we put there.
     */
    @Volatile
    private var requestedCpus: Map<Int, Int> = emptyMap()

    /**
     * The Adreno's own bounds before Mimir touched them, and the level Mimir asked for.
     *
     * **This was missing and the GPU row shipped without it.** `gpuCeilingCommand` writes BOTH
     * `min_pwrlevel` and `max_pwrlevel`, while `captureOnce` covered only the CPU QoS node, `withdraw`
     * restored only CPUs and `withdrawOnColdStart` filtered to `Kind.CPU`. So a GPU cap could be
     * applied and nothing in this file could ever take it back — a control offering a ceiling it could
     * not reverse, which is precisely the failure this whole path was written to prevent. Caught by the
     * merge gate reading the shipped code rather than the intent.
     *
     * Both bounds are captured because both are written: restoring only the ceiling would leave the
     * floor where we widened it to, and the floor is what lets the GPU idle down.
     */
    private data class GpuBounds(val minLevel: Int, val maxLevel: Int)

    @Volatile
    private var capturedGpu: GpuBounds? = null

    @Volatile
    private var requestedGpu: GpuBounds? = null

    /** The row this process would restore, or null if it has never written anything. */
    internal fun captured(): String? = capturedCpuRow

    /**
     * Is Mimir holding a ceiling of its own right now?
     *
     * Either axis counts, because either is something the player is owed a way back from. Asked of the
     * CAPTURES rather than of the live nodes: a capture exists exactly when we have written and not yet
     * withdrawn, which is the question, and reading the nodes would answer a different one — whether a
     * cap is in force, which the vendor governor can change without us.
     */
    fun isHolding(): Boolean = capturedCpuRow != null || capturedGpu != null

    /**
     * Where the session is written down so it can outlive this process .
     *
     * **A process-scoped collaborator rather than a per-call parameter, and the reason is not style.**
     * `nodes` and `host` are per-call because they are pure functions of their arguments; the record is
     * a live transport that is bound once, at startup, exactly like [PServerHost]. Threading it through
     * every signature would also have forced a choice between breaking the trailing-lambda call sites
     * and breaking the positional ones — `host` is the last parameter at some call sites and the fourth
     * at others, so no single ordering keeps both. A collaborator keeps every existing caller and every
     * existing test compiling untouched, which is what makes "the no-daemon path is unchanged" a fact
     * the suite proves rather than an unsupported claim.
     */
    @Volatile
    internal var recordStore: MimirTunerRecordStore = NoTunerRecordStore

    /** Test seam. The device path has no reason to reset, but a test that could not would be useless. */
    internal fun forgetCapture() {
        capturedCpuRow = null
        requestedCpus = emptyMap()
        capturedGpu = null
        requestedGpu = null
        recordStore = NoTunerRecordStore
        // The deferred reconcile is a once-per-PROCESS event (see [reconcileDaemonRouteDeferred]), so
        // without these three a single test that let it fire would silently disarm it for every test
        // that ran afterwards -- and each of those would pass for the wrong reason.
        coldStartVerdict = null
        deferredDaemonReconcileDone.set(false)
        mutationsInFlight.set(0)
    }

    /**
     * Which boot this is, for [MimirTunerSnapshot.bootIdentity].
     *
     * **The fallback exists because the primary read is UNVERIFIED on this hardware.**
     * `/proc/sys/kernel/random/boot_id` is world-readable on ordinary Android builds, but this project
     * has been burned once already by reasoning about a sysfs node instead of reading it (the mtime
     * premise withdrawn at `:183-195`), so this does not assume. When the node is unreadable the
     * identity falls back to the wall-clock instant the device booted — `now - elapsedRealtime`,
     * bucketed so that ordinary clock jitter does not move it. The fallback can drift under an NTP
     * step, and the consequence of drift is that a live record stops matching and cold start widens
     * instead of adopting: the SAFE direction, and the same thing that happens with no daemon at all.
     */
    internal fun bootIdentity(nodes: MimirTunerNodes): String =
        nodes.read(BootIdNode)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "elapsed:${(System.currentTimeMillis() - SystemClock.elapsedRealtime()) / BootIdentityBucketMs}"

    /** Ten seconds: far longer than boot-time clock settling, far shorter than any real session. */
    private const val BootIdentityBucketMs = 10_000L

    /** `{0 to 2016000, …}` → `0:2016000 …`. The node's own spelling, in one place, so the record and
     *  the write can never disagree about how a row is written down. */
    internal fun formatCpuQosRow(row: Map<Int, Int>): String =
        row.keys.sorted().joinToString(" ") { "$it:${row.getValue(it)}" }

    /** This process's live session, in the form that survives it. */
    internal fun snapshotOf(nodes: MimirTunerNodes): MimirTunerSnapshot = MimirTunerSnapshot(
        bootIdentity = bootIdentity(nodes),
        cpuStockRow = capturedCpuRow,
        cpuRequested = requestedCpus,
        gpuStockMinLevel = capturedGpu?.minLevel,
        gpuStockMaxLevel = capturedGpu?.maxLevel,
        gpuRequestedMinLevel = requestedGpu?.minLevel,
        gpuRequestedMaxLevel = requestedGpu?.maxLevel,
    )

    /**
     * Take up a session a previous Mimir process left behind, as though this process had written it.
     *
     * **Adoption, not withdrawal, and that distinction is the whole behaviour.** The cap is supposed to
     * still be in force after a kill — the player set it, nobody asked for it back, and a control that
     * undid itself because the app restarted would be a control acting while nobody touched it. So the
     * fields are rehydrated and the session simply continues: `isHolding` reports true, the surface
     * reads the same in-force number it read before, and the player's own withdraw — whenever they ask
     * for it — now returns the EXACT stock value instead of widening to the silicon's ceiling.
     *
     * **The merge rule, for the one case where both sides hold something.** If the daemon's answer
     * arrives after this process has already captured (an eligible device whose binding was still
     * resolving when the player moved a stepper), the daemon's STOCK wins: it was taken when nothing of
     * ours was in force, while this process's capture was taken with the previous session's cap still
     * on the node and is therefore contaminated — the precise bug `releaseAnyOrphanedCap` exists to
     * prevent, arriving by a different road. The REQUESTED maps are merged with this process's entries
     * winning, because a CPU this process has written since is held at this process's value.
     */
    /**
     * Is anything the record claims Mimir wrote still on the hardware?
     *
     * **The same predicate `withdraw` already applies, asked one step earlier.** Withdrawal restores only
     * the CPUs still holding OUR value, so a record none of whose values survive would adopt into a
     * session whose every restore is a no-op — `isHolding()` true, a withdraw offered, and nothing given
     * back when it is pressed. Asking here turns that into a discard.
     */
    internal fun recordedSessionStillInForce(
        snapshot: MimirTunerSnapshot,
        nodes: MimirTunerNodes,
    ): Boolean {
        val liveCpu = nodes.read(CpuQosNode)?.let(::parseCpuQosRow).orEmpty()
        if (snapshot.cpuRequested.any { (cpuId, wrote) -> liveCpu[cpuId] == wrote }) return true
        val wroteMin = snapshot.gpuRequestedMinLevel ?: return false
        val wroteMax = snapshot.gpuRequestedMaxLevel ?: return false
        val liveMin = nodes.read("$GpuRoot/min_pwrlevel")?.trim()?.toIntOrNull()
        val liveMax = nodes.read("$GpuRoot/max_pwrlevel")?.trim()?.toIntOrNull()
        return liveMin == wroteMin && liveMax == wroteMax
    }

    internal fun adoptRecordedSession(snapshot: MimirTunerSnapshot) {
        synchronized(this) {
            capturedCpuRow = snapshot.cpuStockRow ?: capturedCpuRow
            requestedCpus = snapshot.cpuRequested + requestedCpus
            val stockMin = snapshot.gpuStockMinLevel
            val stockMax = snapshot.gpuStockMaxLevel
            if (stockMin != null && stockMax != null) capturedGpu = GpuBounds(stockMin, stockMax)
            val wroteMin = snapshot.gpuRequestedMinLevel
            val wroteMax = snapshot.gpuRequestedMaxLevel
            if (requestedGpu == null && wroteMin != null && wroteMax != null) {
                requestedGpu = GpuBounds(wroteMin, wroteMax)
            }
        }
        // **The row, not just its size, and the boot it belongs to.** `cpus=2` says a session was
        // adopted and nothing about WHICH one, so a capture could not tell the daemon's held ceiling
        // from a stale record adopted by mistake -- the two states this path exists to keep apart. The
        // count stays where it was because the device scripts of 2026-09-01 grep this line's prefix and
        // the evidence index reads the field; the identity is ADDED beside it.
        Log.i(
            TAG,
            "adopted a recorded session: boot=${snapshot.bootIdentity} cpus=${requestedCpus.size} " +
                "requested=[${formatCpuQosRow(requestedCpus)}] gpu=${requestedGpu != null}",
        )
    }

    /**
     * Mirror the session the DAEMON says it is holding into this process.
     *
     * **The same merge as [adoptRecordedSession], deliberately by delegation rather than by copy.** The
     * daemon's stock wins over a capture this process took, its requested map is merged with ours
     * winning per CPU, and the GPU bounds are taken only when both halves are present — three rules
     * that have already cost this file one device incident each, and a second implementation of them is
     * a second place for them to drift.
     *
     * **Nothing here checks the boot identity, and that is not an omission.** On this route the daemon
     * owns the session and is boot-bound in its own store (contract: *"a ceiling is player-owned and
     * boot-bound"*), so a record surviving a reboot is a defect on the side that kept it, not something
     * this mirror can second-guess: it has no capture of its own to compare against.
     */
    internal fun adoptDaemonRecord(record: DaemonCeilingRecord) {
        Log.i(
            TAG,
            "mirroring the daemon's session: boot=${record.bootIdentity} " +
                "cpus=${record.cpuRequested.size} gpu=${record.gpuRequestedMinLevel != null}",
        )
        adoptRecordedSession(
            MimirTunerSnapshot(
                bootIdentity = record.bootIdentity,
                cpuStockRow = record.cpuStockRow,
                cpuRequested = record.cpuRequested,
                gpuStockMinLevel = record.gpuStockMinLevel,
                gpuStockMaxLevel = record.gpuStockMaxLevel,
                gpuRequestedMinLevel = record.gpuRequestedMinLevel,
                gpuRequestedMaxLevel = record.gpuRequestedMaxLevel,
            ),
        )
    }

    /**
     * Forget what the mirror says we hold, WITHOUT touching the v9 record.
     *
     * The separation from [forgetCapture] is the point: this runs on the daemon route, where the record
     * is the daemon's and a `clear()` from here would be this process writing a store it does not own.
     */
    private fun clearMirror() {
        synchronized(this) {
            capturedCpuRow = null
            requestedCpus = emptyMap()
            capturedGpu = null
            requestedGpu = null
        }
    }

    /**
     * How many player-initiated writes are between their first line and their last, right now.
     *
     * **It exists for exactly one reader: [reconcileDaemonRouteDeferred].** The deferred reconcile is
     * the only thing in this file that speaks to the daemon without a player having asked for
     * anything, so it is the only thing that can arrive in the middle of somebody else's transaction --
     * and on the daemon route a `query` and an `apply` want different reservation kinds, so an
     * overlapping pair costs one of them its answer (`TweakerSessionReservation.requestOwner` REJECTS
     * a settlement while a mutation owns the transport). Counting at the PUBLIC entry points rather
     * than around the actuator call is deliberate: the route can move between the two, and what the
     * guard must mean is "the player is mid-press", not "a Binder call is open".
     *
     * An Int rather than a flag because two surfaces can ask at once -- the companion's stepper and the
     * dev hook, which is precisely the pair the Thor's 08:30:00 trace caught racing.
     */
    private val mutationsInFlight = AtomicInteger(0)

    /** Inline so a non-local `return` from one of the wrapped bodies still leaves the count balanced. */
    private inline fun <T> whileMutating(body: () -> T): T {
        mutationsInFlight.incrementAndGet()
        try {
            return body()
        } finally {
            mutationsInFlight.decrementAndGet()
        }
    }

    /**
     * Request [ceilingKhz] on [policy], and report what is actually in force afterwards.
     *
     * Reports the live reading from `scaling_max_freq` — NOT the value asked for. A successful
     * transaction is not a changed clock: `PServerHost.run` reports only that the command was
     * delivered, and the HAL and thermal both get a say after we have spoken. Every caller must render
     * what comes back rather than what it sent.
     *
     * **the daemon owns the node whenever it is present.** The route is
     * decided in exactly one place ([MimirTunerDaemonRoute.decision]) and read ONCE here, at the top,
     * then carried in the outcome — see `MimirTunerRoute.kt` for the table and why a guess is never
     * allowed to become a write. Three arms, and only one of them touches this device's sysfs:
     *
     * - **DIRECT** — today's path, byte for byte: capture, write, upsert the v9 record, read back.
     * - **DAEMON** — the daemon writes; this process keeps only a MIRROR of what it reports holding, so
     *   [isHolding] and [captured] answer the same questions they always did. The v9 record is NOT
     *   written on this route: the daemon owns the record as well as the node, and two writers keeping
     *   two records is the state this ruling exists to end.
     * - **REFUSE** — nothing is known about the daemon yet, or it exists and cannot be reached.
     *   Nothing is written and nothing is captured, because writing around a daemon that may be holding
     *   the node is precisely the two-writer failure recorded in this file's header.
     *
     * The snap onto a published frequency happens BEFORE the route split and on every arm. It is the
     * only door a number can come through — see [nearestSupportedKhz] — and a debug hook must not be
     * able to hand the daemon an unpublished value merely because the daemon would re-admit it anyway.
     */
    fun requestCeiling(
        policy: MimirTunerPolicy,
        ceilingKhz: Int,
        nodes: MimirTunerNodes = MimirTunerBackend.nodes(),
        host: (String) -> Boolean = MimirTunerBackend.host,
    ): MimirTunerWriteOutcome = whileMutating {
        requestCeilingRouted(policy, ceilingKhz, nodes, host)
    }

    /**
     * The routed request itself, byte for byte what [requestCeiling] was before the counter wrapped it.
     *
     * Split rather than wrapped in place so the three arms below stay literally unchanged: "the direct
     * path is unchanged on a device with no daemon" is a claim this file makes twice, and a reindent is
     * the cheapest possible way to make a reviewer unable to check it.
     */
    private fun requestCeilingRouted(
        policy: MimirTunerPolicy,
        ceilingKhz: Int,
        nodes: MimirTunerNodes,
        host: (String) -> Boolean,
    ): MimirTunerWriteOutcome {
        val route = MimirTunerDaemonRoute.decision()
        val target = nearestSupportedKhz(policy, ceilingKhz) ?: run {
            Log.w(TAG, "request ${policy.label}: $ceilingKhz kHz is not in this device's table; refusing")
            return MimirTunerWriteOutcome.Refused(
                code = MimirTunerRefusalUndiscoveredFrequency,
                detail = "$ceilingKhz kHz is not a published step for ${policy.label}",
                route = route,
            )
        }
        return when (route) {
            MimirTunerRouteDecision.Direct -> requestCeilingDirectly(policy, target, nodes, host, route)
            MimirTunerRouteDecision.Daemon -> requestCeilingThroughDaemon(policy, target, route)
            is MimirTunerRouteDecision.Refuse -> {
                Log.i(TAG, "request ${policy.label} -> $target kHz route=REFUSE reason=${route.reason}")
                MimirTunerWriteOutcome.Refused(
                    code = MimirTunerRefusalRouteRefused,
                    detail = route.reason,
                    route = route,
                )
            }
        }
    }

    /** Today's write, unchanged. The only arm that captures, speaks to [PServerHost] or keeps a record. */
    private fun requestCeilingDirectly(
        policy: MimirTunerPolicy,
        target: Int,
        nodes: MimirTunerNodes,
        host: (String) -> Boolean,
        route: MimirTunerRouteDecision,
    ): MimirTunerWriteOutcome {
        captureOnce(policy, nodes)
        val command = when (policy.kind) {
            MimirTunerPolicy.Kind.CPU -> cpuCeilingCommand(policy, target, nodes)
            MimirTunerPolicy.Kind.GPU -> gpuCeilingCommand(policy, target)
        }
        val delivered = host(command)
        if (delivered && policy.kind == MimirTunerPolicy.Kind.CPU) {
            requestedCpus = requestedCpus + affectedCpuIds(policy, nodes).associateWith { target }
        }
        if (delivered && policy.kind == MimirTunerPolicy.Kind.GPU) {
            requestedGpu = GpuBounds(
                minLevel = policy.availableKhz.lastIndex.coerceAtLeast(
                    gpuPowerLevelForKhz(policy.availableKhz, target),
                ),
                maxLevel = gpuPowerLevelForKhz(policy.availableKhz, target),
            )
        }
        // The record is upserted on EVERY delivered write, not only on the first capture. A stepped
        // session moves `requestedCpus`, and a record still naming the first step would, after a kill,
        // treat this process's own second write as somebody else's and quietly decline to restore it.
        if (delivered) recordStore.write(snapshotOf(nodes))
        val inForce = readInForceKhz(policy, nodes)
        Log.i(
            TAG,
            "request ${policy.label} -> $target kHz route=DIRECT delivered=$delivered inForce=$inForce",
        )
        return MimirTunerWriteOutcome.InForce(khz = inForce, admittedKhz = target, route = route)
    }

    /**
     * The daemon writes; this process mirrors what it says it holds.
     *
     * **The mirror is bookkeeping, not a second record.** It exists so [isHolding] and [captured] keep
     * answering for the surface without asking who wrote, and so a later DIRECT withdrawal on a device
     * whose daemon went away still knows which CPUs are ours. Nothing here reaches sysfs and nothing
     * here writes [recordStore].
     */
    private fun requestCeilingThroughDaemon(
        policy: MimirTunerPolicy,
        target: Int,
        route: MimirTunerRouteDecision,
    ): MimirTunerWriteOutcome {
        val actuator = MimirTunerDaemonRoute.actuator
        if (actuator == null) {
            // A programming error, not a device state: the bridge installs `presence` and `actuator`
            // together. Reported rather than thrown, because throwing from a stepper press would take
            // the app down over a wiring mistake that leaves the hardware untouched.
            Log.w(TAG, "request ${policy.label}: route=DAEMON with no actuator installed; nothing written")
            return MimirTunerWriteOutcome.Unavailable(reason = NoActuatorReason, route = route)
        }
        return when (val answer = actuator.apply(policy.kind, policy.id, target)) {
            is DaemonCeilingOutcome.Applied -> {
                val record = answer.record
                if (record != null) {
                    adoptDaemonRecord(record)
                } else {
                    // The daemon applied and told us nothing about the session. The mirror is left
                    // ALONE rather than invented: a stock row this process did not observe is the one
                    // value it must never make up. Cold start's `query` adopts it on the next launch.
                    Log.w(TAG, "request ${policy.label}: the daemon applied but named no record; mirror unchanged")
                }
                Log.i(
                    TAG,
                    "request ${policy.label} -> $target kHz route=DAEMON " +
                        "admitted=${answer.admittedKhz} inForce=${answer.inForceKhz}",
                )
                MimirTunerWriteOutcome.InForce(
                    khz = answer.inForceKhz,
                    admittedKhz = answer.admittedKhz,
                    route = route,
                )
            }
            is DaemonCeilingOutcome.Refused -> {
                Log.i(TAG, "request ${policy.label} route=DAEMON refused=${answer.code} ${answer.detail}")
                MimirTunerWriteOutcome.Refused(answer.code, answer.detail, route)
            }
            is DaemonCeilingOutcome.Unavailable -> {
                Log.i(TAG, "request ${policy.label} route=DAEMON unavailable=${answer.reason}")
                MimirTunerWriteOutcome.Unavailable(answer.reason, route)
            }
            else -> {
                // WITHDRAWN / NOTHING_HELD / HELD / NOT_HELD are answers to other questions. Treated as
                // "nobody judged this request" rather than silently read as success.
                Log.w(TAG, "request ${policy.label} route=DAEMON answered $answer, which is not an apply outcome")
                MimirTunerWriteOutcome.Unavailable(reason = UnexpectedAnswerReason, route = route)
            }
        }
    }

    /** The bridge installs `presence` and `actuator` together; one without the other is a wiring bug. */
    private const val NoActuatorReason = "no actuator"

    /** The daemon answered a question nobody asked. */
    private const val UnexpectedAnswerReason = "unexpected daemon answer"

    /**
     * Give the device back what it had.
     *
     * Safe to call when nothing was ever requested — it returns without speaking. A withdraw that
     * asserted a default "just in case" would be a write dressed as a restore, and on the Thor it would
     * have moved six CPUs off their boot values.
     *
     * **Routed exactly like the request, and for the stronger reason.** Whoever wrote the cap owns the
     * way back: asking the daemon to restore a row this process never captured, or stamping this
     * process's capture over a row the daemon is holding, are the same two-writer failure in opposite
     * directions. The decision is read once, at the top, and carried out in the outcome.
     */
    fun withdraw(
        nodes: MimirTunerNodes = MimirTunerBackend.nodes(),
        host: (String) -> Boolean = MimirTunerBackend.host,
    ): MimirTunerWriteOutcome = whileMutating {
        withdrawRouted(nodes, host)
    }

    /** The routed withdrawal itself, unchanged; see [requestCeilingRouted] for why it is split out. */
    private fun withdrawRouted(
        nodes: MimirTunerNodes,
        host: (String) -> Boolean,
    ): MimirTunerWriteOutcome {
        val route = MimirTunerDaemonRoute.decision()
        return when (route) {
            MimirTunerRouteDecision.Direct -> withdrawDirectly(nodes, host, route)
            MimirTunerRouteDecision.Daemon -> withdrawThroughDaemon(route)
            is MimirTunerRouteDecision.Refuse -> {
                // The way back is never forced through a guess either. A device holding a cap keeps it
                // until the route resolves, which is the same state it was in one second earlier.
                Log.i(TAG, "withdraw route=REFUSE reason=${route.reason}")
                MimirTunerWriteOutcome.Refused(
                    code = MimirTunerRefusalRouteRefused,
                    detail = route.reason,
                    route = route,
                )
            }
        }
    }

    /**
     * Today's restore, unchanged, typed.
     *
     * **"Nothing was held" is asked BEFORE the restore runs, not inferred from it.** The old boolean
     * answered true both for *"the row went back"* and for *"there was nothing to put back"*, and the
     * surface then had to guess which it meant. Asking [isHolding] on entry separates them without
     * changing a single command this function sends.
     */
    private fun withdrawDirectly(
        nodes: MimirTunerNodes,
        host: (String) -> Boolean,
        route: MimirTunerRouteDecision,
    ): MimirTunerWriteOutcome {
        val heldOnEntry = isHolding()
        val result = withdrawInternal(nodes, host)
        // Reconciled from the process's own state rather than from [result], because the outcomes that
        // matter here are not the same question. Several paths give the device back and report false,
        // and one — "nothing of ours is still in force" — gives nothing back and reports true. What
        // decides whether a record should outlive this call is whether anything is still held, and a
        // record that outlives the session it describes is the stale capture this change exists to end.
        if (!isHolding()) recordStore.clear()
        return when {
            !heldOnEntry -> MimirTunerWriteOutcome.NothingToRelease(route)
            result.delivered -> MimirTunerWriteOutcome.Released(
                restoredCpus = result.restoredCpus,
                leftAloneCpus = result.leftAloneCpus,
                gpuRestored = result.gpuRestored,
                route = route,
            )
            // The capture is deliberately still held here — see the failed-restore branch below — so
            // this is not the end of the session, it is a restore that did not land.
            else -> MimirTunerWriteOutcome.Unavailable(reason = RestoreNotDeliveredReason, route = route)
        }
    }

    /** The privileged host would not take the restore. The capture survives for the next attempt. */
    private const val RestoreNotDeliveredReason = "restore not delivered"

    /**
     * Ask the daemon to give the device back, and clear the mirror on its word.
     *
     * The mirror is cleared on WITHDRAWN and on NOTHING_HELD because both mean the daemon is holding
     * nothing for us afterwards. It is left untouched on a refusal or an unreachable transport: a
     * mirror cleared on silence would report `isHolding() == false` while a cap is still on the node,
     * which is the one lie this whole file is built to avoid.
     */
    private fun withdrawThroughDaemon(route: MimirTunerRouteDecision): MimirTunerWriteOutcome {
        val actuator = MimirTunerDaemonRoute.actuator
        if (actuator == null) {
            Log.w(TAG, "withdraw: route=DAEMON with no actuator installed; nothing withdrawn")
            return MimirTunerWriteOutcome.Unavailable(reason = NoActuatorReason, route = route)
        }
        return when (val answer = actuator.withdraw()) {
            is DaemonCeilingOutcome.Withdrawn -> {
                clearMirror()
                Log.i(
                    TAG,
                    "withdraw route=DAEMON restored=${answer.restoredCpus} " +
                        "leftAlone=${answer.leftAloneCpus} gpu=${answer.gpuRestored}",
                )
                MimirTunerWriteOutcome.Released(
                    restoredCpus = answer.restoredCpus,
                    leftAloneCpus = answer.leftAloneCpus,
                    gpuRestored = answer.gpuRestored,
                    route = route,
                )
            }
            DaemonCeilingOutcome.NothingHeld -> {
                clearMirror()
                Log.i(TAG, "withdraw route=DAEMON nothing held")
                MimirTunerWriteOutcome.NothingToRelease(route)
            }
            is DaemonCeilingOutcome.Refused -> {
                Log.i(TAG, "withdraw route=DAEMON refused=${answer.code} ${answer.detail}")
                MimirTunerWriteOutcome.Refused(answer.code, answer.detail, route)
            }
            is DaemonCeilingOutcome.Unavailable -> {
                Log.i(TAG, "withdraw route=DAEMON unavailable=${answer.reason}")
                MimirTunerWriteOutcome.Unavailable(answer.reason, route)
            }
            else -> {
                Log.w(TAG, "withdraw route=DAEMON answered $answer, which is not a withdraw outcome")
                MimirTunerWriteOutcome.Unavailable(reason = UnexpectedAnswerReason, route = route)
            }
        }
    }

    /**
     * What the direct restore did, in the terms the outcome reports.
     *
     * [delivered] is the OLD boolean, unchanged and deliberately so — including the case where nothing
     * of ours was still in force, which speaks to no node and still reports true, because the session
     * genuinely ended there.
     */
    private data class DirectWithdrawal(
        val delivered: Boolean,
        val restoredCpus: Int,
        val leftAloneCpus: Int,
        val gpuRestored: Boolean,
    )

    private fun withdrawInternal(
        nodes: MimirTunerNodes,
        host: (String) -> Boolean,
    ): DirectWithdrawal {
        // The GPU is restored FIRST and on its own terms: it is a different node, a different unit and a
        // different owner, and folding it into the CPU row's early return is exactly how it came to have
        // no restore at all.
        val gpuRestored = withdrawGpu(nodes, host)
        val captured = capturedCpuRow?.let(::parseCpuQosRow)?.takeIf { it.isNotEmpty() }
            ?: return DirectWithdrawal(
                delivered = gpuRestored,
                restoredCpus = 0,
                leftAloneCpus = 0,
                gpuRestored = gpuRestored,
            )
        val live = nodes.read(CpuQosNode)?.let(::parseCpuQosRow).orEmpty()
        // Restore only the CPUs still holding OUR value. A CPU that has moved since we wrote it was
        // moved by the other writer, and stamping our captured value over theirs would be Mimir
        // undoing a change it never made and cannot see -- worse than leaving the cap, because it is
        // invisible and looks like a restore succeeding.
        val restored = live.toMutableMap()
        var ours = 0
        var foreign = 0
        requestedCpus.forEach { (cpuId, wrote) ->
            val stock = captured[cpuId] ?: return@forEach
            if (live[cpuId] == wrote) {
                restored[cpuId] = stock
                ours += 1
            } else {
                foreign += 1
            }
        }
        if (ours == 0) {
            Log.i(TAG, "withdraw: nothing of ours is still in force (foreign=$foreign)")
            capturedCpuRow = null
            requestedCpus = emptyMap()
            return DirectWithdrawal(
                delivered = true,
                restoredCpus = 0,
                leftAloneCpus = foreign,
                gpuRestored = gpuRestored,
            )
        }
        val row = formatCpuQosRow(restored)
        val delivered = host(writeCommand(CpuQosNode, row))
        Log.i(TAG, "withdraw -> $row delivered=$delivered restored=$ours leftAlone=$foreign")
        if (delivered) {
            capturedCpuRow = null
            requestedCpus = emptyMap()
        }
        return DirectWithdrawal(
            delivered = delivered && gpuRestored,
            restoredCpus = if (delivered) ours else 0,
            leftAloneCpus = foreign,
            gpuRestored = gpuRestored,
        )
    }

    /**
     * Put the Adreno's bounds back, if Mimir moved them and nothing else has since.
     *
     * Same rule as the CPU half: restore only what is still ours. If the levels in force are not the
     * ones we wrote, somebody else owns them now and stamping our capture over theirs would be Mimir
     * undoing a change it never made.
     *
     * Order matters on the way back too, and it is the REVERSE of the way out. Applying widened the
     * floor before lowering the ceiling, because the kernel clamps `max_pwrlevel` against
     * `min_pwrlevel`; restoring must raise the ceiling before narrowing the floor, or the floor we are
     * putting back would clamp the ceiling we just restored.
     */
    private fun withdrawGpu(nodes: MimirTunerNodes, host: (String) -> Boolean): Boolean {
        val stock = capturedGpu ?: return true
        val ours = requestedGpu ?: return true
        val liveMin = nodes.read("$GpuRoot/min_pwrlevel")?.trim()?.toIntOrNull()
        val liveMax = nodes.read("$GpuRoot/max_pwrlevel")?.trim()?.toIntOrNull()
        if (liveMin != ours.minLevel || liveMax != ours.maxLevel) {
            Log.i(TAG, "withdraw gpu: bounds are no longer ours (live=$liveMin/$liveMax ours=$ours)")
            capturedGpu = null
            requestedGpu = null
            return true
        }
        val delivered = host(
            listOf(
                writeCommand("$GpuRoot/max_pwrlevel", stock.maxLevel.toString()),
                writeCommand("$GpuRoot/min_pwrlevel", stock.minLevel.toString()),
            ).joinToString("; "),
        )
        Log.i(TAG, "withdraw gpu -> min=${stock.minLevel} max=${stock.maxLevel} delivered=$delivered")
        if (delivered) {
            capturedGpu = null
            requestedGpu = null
        }
        return delivered
    }

    /**
     * A cold-start withdraw, for the one case boot does not cover: Mimir died holding a cap and the
     * player has not rebooted since.
     *
     * **It cannot use a capture, because the capture died with the process** — so it deliberately does
     * NOTHING unless a cap is visibly in force, and even then it only widens to the cluster's own
     * ceiling, which is the one value that is safe without knowing stock: requesting a ceiling AT the
     * ceiling restricts nothing, and cannot overclock anything, because the ceiling is what the silicon
     * published in the first place.
     */
    /**
     * Run the cold-start withdraw once, at process start, before anything can capture.
     *
     * **This was written and then not wired in, and the device charged for it within the hour.** A
     * previous Mimir process left CPUs 0-2 capped at 1 459 200 and died. The next process opened, took
     * its capture, and recorded **that cap as stock** — so a later withdrawal faithfully restored the
     * cap, logging `delivered=true` while putting the handheld back to the wrong number. A restore that
     * succeeds at restoring the wrong value is worse than one that fails, because nothing anywhere says
     * so.
     *
     * **Ordering is the whole fix.** This must run before [requestCeiling] can ever be called, which is
     * why it sits in `onCreate` rather than anywhere convenient: the capture is only meaningful if
     * nothing of ours is already in force when it is taken.
     *
     * **The presence check goes through [MimirTunerBackend], and that routing is load-bearing.** The
     * guard below is the earliest point on the cold-start path, so a device with no privileged host
     * returns here and never reaches [reconcileColdStart] at all. Asking [PServerHost] directly would
     * have made every seam further downstream unreachable on exactly the devices a portable proof needs
     * to run on. On real hardware this reads [PServerHost.available] and nothing has changed.
     */
    fun releaseAnyOrphanedCap(
        nodes: MimirTunerNodes = MimirTunerBackend.nodes(),
        host: (String) -> Boolean = MimirTunerBackend.host,
    ) {
        if (!MimirTunerBackend.hostAvailable()) return
        reconcileColdStart(discoverTunerPolicies(nodes), nodes, recordStore, host)
    }

    /**
     * What a cold start does about a cap a previous process left in force, now that the record can
     * outlive the process.
     *
     * **There are three answers and the third is the one that needed thinking about.**
     *
     * - **A record is held** → [adoptRecordedSession]. The cap stays in force, because the player set
     *   it and has not asked for it back; what changes is that the way back is now exact. This is the
     *   whole point of R2 and it is the only branch that is new.
     * - **The daemon answers that it holds nothing** → [withdrawOnColdStart]. A cap in force with no
     *   record is genuinely orphaned, and widening to the silicon's own ceilings is still the only
     *   thing that is safe without knowing stock.
     * - **The daemon cannot be asked** ([MimirTunerRecordRead.Unavailable]) → widen, and **do NOT
     *   clear**. **The safety action is never skipped because a transport was silent** — that would
     *   leave a cap in force on the strength of a timeout. The record is left alone: a store that
     *   answers `Unavailable` is not saying the session ended, and issuing a clear on a silent
     *   transport would forget a session that is still live. Staleness is caught later, at adoption,
     *   which is the only place it can do harm — see the `Unavailable -> Unit` arm in
     *   [reconcileColdStart] and its own comment.
     *
     *   (This bullet said the opposite until 2026-09-01 — "ask the store to forget anything it might
     *   hold … clearing it is the honest bookkeeping" — while the code five lines away deliberately did
     *   not clear. An adversarial review caught the contradiction. The CODE was right; the doc has been
     *   corrected to it rather than the other way round.)
     *
     * **Ordering, which is the part an IPC makes interesting.** Widening first and asking afterwards
     * would make the record permanently moot on every eligible device, because the adoption test is
     * "are the CPUs still holding what we recorded?" and widening is precisely what stops them. So on
     * a device where a daemon could answer, the widen WAITS for the answer. On a device where one
     * cannot, there is nothing to wait for and the behaviour is byte-identical to before this existed.
     *
     * **Every path out of here now names itself in one INFO line, and the silent branch is why.** Four
     * of the five outcomes already logged something; the fifth — record absent, nothing in force,
     * nothing to do — logged nothing at all, so "the reconcile ran and decided to do nothing" and "the
     * reconcile never ran" were indistinguishable in a capture. That is the difference between a proof
     * and a shrug, and it is also the sync point a device script needs in order to know the cold start
     * has finished before it asserts anything.
     *
     * The name is the outcome, in precedence order: an adoption if one happened, else a widen if one
     * was delivered, else the reason a held record was thrown away, else quiet. Widen outranks discard
     * because the widen is the half that MOVED the hardware, and an operator reading one line should
     * see the write before the bookkeeping.
     *
     * **The route decides WHICH of those questions is even asked (2026-09-11).** Everything above
     * describes the DIRECT arm and is unchanged on a device with no daemon, which is the floor this can
     * never fall below. The full table lives in `MimirTunerRoute.kt`; here it means:
     *
     * - **DIRECT** → the reconcile above, with one new way out: an old daemon reporting a live named
     *   profile ([MimirTunerDaemonPresence.profileSessionActive]) is a writer holding the row on
     *   purpose, so nothing is widened and the branch says `profile_active`.
     * - **DAEMON** → the daemon is asked (`query`) and NOTHING is widened on any answer: `Held` mirrors
     *   its record (`adopted_daemon`), `NotHeld` leaves the node alone (`daemon_owned`), an unreachable
     *   daemon leaves everything exactly as it found it (`daemon_unreachable`).
     * - **REFUSE** → one line, `daemon_unresolved`, and no write of any kind. The device keeps whatever
     *   it was holding one second earlier, which is the only honest thing to do with an unknown owner.
     *
     * `route=DIRECT|DAEMON|REFUSE` joins the one INFO line for the same reason the line exists at all:
     * a device script that can read the branch but not the writer cannot tell a clean boot on a
     * daemonless handheld from a reconcile that declined to act.
     */
    internal fun reconcileColdStart(
        policies: List<MimirTunerPolicy>,
        nodes: MimirTunerNodes = MimirTunerBackend.nodes(),
        record: MimirTunerRecordStore = NoTunerRecordStore,
        host: (String) -> Boolean = MimirTunerBackend.host,
    ): Boolean {
        // A store other than the no-daemon default means a Tweaker answered at bind time. Reported
        // because a proof that cannot tell "the daemon declined" from "there was no daemon" is a proof
        // that will eventually be read as the wrong one.
        val daemonStore = record !== NoTunerRecordStore
        // Read ONCE, here, and carried into every branch below. Asking twice inside one cold start
        // could straddle the moment the daemon answers the bridge and produce a reconcile that widened
        // on one line and adopted on the next.
        val route = MimirTunerDaemonRoute.decision()
        return when (route) {
            MimirTunerRouteDecision.Direct ->
                reconcileColdStartDirectly(policies, nodes, record, host, daemonStore, route)
            MimirTunerRouteDecision.Daemon -> reconcileColdStartThroughDaemon(daemonStore, route)
            is MimirTunerRouteDecision.Refuse -> {
                // Nothing is known about who owns the node, so nothing is widened and nothing is
                // thrown away. A cap stays in force -- which is the state the device was already in.
                logColdStartBranch(DaemonUnresolvedBranch, daemonStore, route)
                false
            }
        }
    }

    /**
     * The daemon owns the node, so cold start ASKS rather than reconciles.
     *
     * **No arm of this widens, and that is the ruling rather than an optimisation.** A cap found in
     * force on a device with a v10 daemon is the daemon's until it says otherwise; widening it would be
     * Mimir overriding the authoritative writer, on the strength of a node reading that
     * cannot name who wrote it.
     */
    private fun reconcileColdStartThroughDaemon(
        daemonStore: Boolean,
        route: MimirTunerRouteDecision,
        deferred: Boolean = false,
    ): Boolean {
        val actuator = MimirTunerDaemonRoute.actuator
        if (actuator == null) {
            Log.w(TAG, "cold start: route=DAEMON with no actuator installed")
            logColdStartBranch(DaemonUnreachableBranch, daemonStore, route, deferred)
            return false
        }
        val branch = when (val answer = actuator.query()) {
            is DaemonCeilingOutcome.Held -> {
                adoptDaemonRecord(answer.record)
                AdoptedDaemonBranch
            }
            // The daemon answered, and it holds nothing. The node is its own; nothing here is orphaned.
            DaemonCeilingOutcome.NotHeld -> DaemonOwnedBranch
            // Silence is not "holds nothing". Nothing is adopted, nothing is widened, nothing is
            // cleared: the evidence is simply not in yet, and destroying it would be irreversible.
            is DaemonCeilingOutcome.Unavailable -> DaemonUnreachableBranch
            else -> {
                Log.w(TAG, "cold start: the daemon answered $answer, which is not a query outcome")
                DaemonUnresolvedBranch
            }
        }
        logColdStartBranch(branch, daemonStore, route, deferred)
        return false
    }

    /**
     * The cold start's verdict, so a LATER answer can tell a finished reconcile from an unfinished one.
     *
     * Written by [logColdStartBranch] -- the single exit every branch already goes through -- so this
     * cannot drift from what the device's own capture says happened.
     */
    @Volatile
    private var coldStartVerdict: String? = null

    /** At most one deferred reconcile per process, whatever happens afterwards. */
    private val deferredDaemonReconcileDone = AtomicBoolean(false)

    /**
     * Is the daemon still owed the question the cold start could not ask?
     *
     * **Odin 3, 2026-09-11, and the two lines are 267 ms apart:**
     *
     * ```
     * 12:33:10.801  cold start reconcile: branch=daemon_unresolved daemonStore=false route=REFUSE
     * 12:33:11.068  route presence pairCanBind=true apiVersion=10 profileActive=false decision=Daemon
     * ```
     *
     * The reconcile read the route ONCE, at the top, as it must -- a cold start that asked twice could
     * widen on one line and adopt on the next -- and at that instant the bind had not answered. So the
     * `query` that adopts a held ceiling into the in-process mirror never ran, and nothing anywhere ran
     * it later: the surface reported no session while the daemon held one, for the life of the process.
     * That is not a race that resolves itself, it is a state with no exit.
     *
     * The exit is this predicate, and it is true only in the states where the question is genuinely
     * still open:
     *
     * - **the route is DAEMON** -- never DIRECT (a device whose writer is this process has nothing to
     *   ask) and never REFUSE (still nothing known; the next answer will come back here);
     * - **no request or withdraw is in flight**, see [mutationsInFlight];
     * - **the cold start's verdict leaves the daemon unasked**: `daemon_unresolved` (the route refused,
     *   the Odin trace above), `daemon_unreachable` (a daemon that exists and did not answer), or no
     *   verdict at all yet;
     * - **it has not already run**.
     *
     * Every other verdict is a finished answer: `adopted_daemon` and `daemon_owned` are the daemon's
     * own word, and the direct-route verdicts (`widened`, `quiet`, `profile_active`, the `discarded_*`
     * family) describe a reconcile that ran with this process as the writer.
     */
    internal fun deferredDaemonRouteReconcileOwed(): Boolean =
        MimirTunerDaemonRoute.decision() == MimirTunerRouteDecision.Daemon &&
            !deferredDaemonReconcileDone.get() &&
            mutationsInFlight.get() == 0 &&
            coldStartLeftTheDaemonUnasked()

    private fun coldStartLeftTheDaemonUnasked(): Boolean = when (coldStartVerdict) {
        null, DaemonUnresolvedBranch, DaemonUnreachableBranch -> true
        else -> false
    }

    /**
     * Ask the daemon the cold start's question once more, now that the route has resolved to DAEMON.
     *
     * **The same `query` path, not a second implementation of it.** It calls
     * [reconcileColdStartThroughDaemon] with `deferred = true` and nothing else changes: HELD adopts,
     * NOT_HELD leaves the node alone, an unreachable daemon changes nothing and clears nothing. A
     * second copy of those three rules is a second place for them to drift, and they have already cost
     * this file one device incident each.
     *
     * **`daemonStore=false` is a fact on this route, not a placeholder.** The v9 store is never
     * installed once the decision is DAEMON -- `TweakerBridge.reconcileDiscoveredTunerColdStart`
     * declines to bind it precisely so that two accounts of one session cannot exist -- so there is no
     * store here to report, and the line says so in the same field the cold start's own line uses.
     *
     * **The guard is taken BEFORE the query, and that ordering is load-bearing.** The bridge installs
     * the route from inside the actuator's own round trip, so this call's `query` re-enters
     * `noteDaemonAnswered`; consuming the once-flag first is what makes that re-entry a no-op instead
     * of a recursion.
     *
     * @return true when the query actually ran. False means it was not owed -- the caller asks, it does
     * not decide.
     */
    internal fun reconcileDaemonRouteDeferred(): Boolean {
        // Re-read rather than trusting the caller's: this runs on a background dispatcher, one hop
        // after the answer that triggered it, and a daemon that went away in between must not be
        // written to on the strength of a decision that was true a moment ago.
        val route = MimirTunerDaemonRoute.decision()
        if (route != MimirTunerRouteDecision.Daemon) return false
        if (!deferredDaemonRouteReconcileOwed()) return false
        if (!deferredDaemonReconcileDone.compareAndSet(false, true)) return false
        reconcileColdStartThroughDaemon(daemonStore = false, route = route, deferred = true)
        return true
    }

    /** Today's reconcile, with one new way out: a live profile session means the cap is not ours. */
    private fun reconcileColdStartDirectly(
        policies: List<MimirTunerPolicy>,
        nodes: MimirTunerNodes,
        record: MimirTunerRecordStore,
        host: (String) -> Boolean,
        daemonStore: Boolean,
        route: MimirTunerRouteDecision,
    ): Boolean {
        var verdict = QuietBranch
        when (val held = record.read()) {
            is MimirTunerRecordRead.Held -> {
                val snapshot = held.snapshot
                val thisBoot = bootIdentity(nodes)
                if (snapshot.isEmpty()) {
                    Log.i(TAG, "cold start: the record names nothing; treating it as absent")
                    record.clear()
                    verdict = "discarded_empty"
                } else if (snapshot.bootIdentity != thisBoot) {
                    // Boot re-establishes the axis, so the record describes a machine that no longer
                    // exists. Discard rather than adopt, then fall through to the ordinary widen --
                    // whatever is in force now was not put there by the session this record describes.
                    Log.w(TAG, "cold start: discarding a record from another boot")
                    record.clear()
                    verdict = "discarded_other_boot"
                } else if (!recordedSessionStillInForce(snapshot, nodes)) {
                    // **A record can outlive the cap it describes**, and adopting one would be a control
                    // claiming to hold something it does not: `isHolding()` would report true, the
                    // companion surface would offer a withdraw (`MimirCompanionPresentation.kt:439`),
                    // and the press would give nothing back. The clear that should have happened may
                    // simply have failed -- it crosses a Binder -- so this is checked at the one moment
                    // it could mislead rather than trusted to have been done earlier.
                    Log.w(TAG, "cold start: the recorded session is no longer in force; discarding")
                    record.clear()
                    verdict = "discarded_stale"
                } else {
                    adoptRecordedSession(snapshot)
                    logColdStartBranch("adopted", daemonStore, route)
                    return false
                }
            }
            MimirTunerRecordRead.Absent -> Unit
            // NOT cleared. A transport that could not be queried cannot be cleared over either, and a
            // store that answers Unavailable on a LATER read is not saying the session ended -- issuing
            // a clear here would forget a session that is still live. Staleness is caught at adoption,
            // above, which is the only place it can do harm.
            MimirTunerRecordRead.Unavailable -> Unit
        }
        // **An OLD daemon running a named profile owns the row it wrote, even on the direct route.**
        // `selectSystemProfileOnWorker` issues the whole eight-CPU row plus the GPU during Mimir's own
        // startup (instrumented 2026-09-01), and every one of those CPUs then reads as "held below its
        // cluster ceiling" -- which is exactly what `withdrawOnColdStart` treats as an orphan. Widening
        // there would undo the player's profile at every launch and report it as a safety action.
        // Nothing of ours is lost by declining: a cap this process did not write is not ours to widen.
        if (MimirTunerDaemonRoute.presence?.profileSessionActive == true) {
            Log.i(TAG, "cold start: a named profile session is live, so the cap belongs to the daemon")
            logColdStartBranch(ProfileActiveBranch, daemonStore, route)
            return false
        }
        val widened = withdrawOnColdStart(policies, nodes, host)
        logColdStartBranch(if (widened) "widened" else verdict, daemonStore, route)
        return widened
    }

    /** The reconcile did not adopt, did not widen and threw nothing away. */
    private const val QuietBranch = "quiet"

    /** The daemon holds a session and this process now mirrors it. */
    private const val AdoptedDaemonBranch = "adopted_daemon"

    /** The daemon answered that it holds nothing. The node is its own; nothing here is orphaned. */
    private const val DaemonOwnedBranch = "daemon_owned"

    /** A daemon exists and could not be asked. Nothing adopted, nothing widened, nothing cleared. */
    private const val DaemonUnreachableBranch = "daemon_unreachable"

    /** Nobody could say who owns the node: the route refused, or the daemon answered off-question. */
    private const val DaemonUnresolvedBranch = "daemon_unresolved"

    /** An OLD daemon is running a named profile, so the row in force is its write and not an orphan. */
    private const val ProfileActiveBranch = "profile_active"

    /**
     * The reconcile's one-line verdict.
     *
     * Deliberately a single call site shape rather than a log at each branch: an operator greps for one
     * literal and gets exactly one line per process, which is what makes it usable as a wait-for
     * condition. Two spellings of the same event would make a `grep -c` ambiguous.
     *
     * [route] joined the line on 2026-09-11 with the daemon route: the branch name alone no longer says
     * who was allowed to write, and a capture showing `branch=quiet` means two different things
     * depending on whether this device had a v10 daemon.
     */
    private fun logColdStartBranch(
        branch: String,
        daemonStore: Boolean,
        route: MimirTunerRouteDecision,
        deferred: Boolean = false,
    ) {
        // Recorded where it is logged, so the fact a later answer reads is the same fact the device
        // capture shows. [deferredDaemonRouteReconcileOwed] is the only reader.
        coldStartVerdict = branch
        Log.i(
            TAG,
            "cold start reconcile: branch=$branch daemonStore=$daemonStore " +
                "route=${mimirTunerRouteLabel(route)}" +
                // **Appended, never a field with two values.** The ordinary line is byte-identical to
                // the one the 2026-09-01 device scripts wait on; stamping the negative case on every
                // launch too would have changed an interface in order to say nothing new.
                if (deferred) " deferred=true" else "",
        )
    }

    fun withdrawOnColdStart(
        policies: List<MimirTunerPolicy>,
        nodes: MimirTunerNodes = MimirTunerBackend.nodes(),
        host: (String) -> Boolean = MimirTunerBackend.host,
    ): Boolean {
        if (capturedCpuRow != null || capturedGpu != null) return false
        // A GPU left capped by a dead process is released the same way a CPU is: widened to the levels
        // the silicon itself publishes, which restrict nothing and cannot overclock. Level 0 is the
        // fastest and the last index is the slowest, so this is the whole published range.
        val gpu = policies.firstOrNull { it.kind == MimirTunerPolicy.Kind.GPU }
        if (gpu != null && gpu.availableKhz.isNotEmpty()) {
            val liveMax = nodes.read("$GpuRoot/max_pwrlevel")?.trim()?.toIntOrNull()
            if (liveMax != null && liveMax > 0) {
                Log.w(TAG, "cold start found a GPU ceiling with no capture (level $liveMax); widening")
                host(
                    listOf(
                        writeCommand("$GpuRoot/min_pwrlevel", gpu.availableKhz.lastIndex.toString()),
                        writeCommand("$GpuRoot/max_pwrlevel", "0"),
                    ).joinToString("; "),
                )
            }
        }
        val cpu = policies.filter { it.kind == MimirTunerPolicy.Kind.CPU }
        if (cpu.isEmpty()) return false
        val row = nodes.read(CpuQosNode)?.trim().orEmpty()
        if (row.isEmpty()) return false
        // Only speak if something is below a ceiling. Silence is the correct output for a clean boot,
        // and it is the common case -- this must not become a write that happens every launch.
        val requested = parseCpuQosRow(row)
        val ceilingFor = cpu.flatMap { policy -> affectedCpuIds(policy).map { it to policy.ceilingKhz } }.toMap()
        val held = requested.any { (cpuId, khz) ->
            val ceiling = ceilingFor[cpuId] ?: return@any false
            khz in 1 until ceiling
        }
        if (!held) return false
        val widened = requested.keys.sorted().joinToString(" ") { cpuId ->
            "$cpuId:${ceilingFor[cpuId] ?: return@joinToString "$cpuId:$UnlimitedKhz"}"
        }
        Log.w(TAG, "cold start found a cap in force with no capture; widening to ceilings: $widened")
        return host(writeCommand(CpuQosNode, widened))
    }

    /**
     * **A CPU held below its own cluster ceiling is NOT stock, whoever is holding it.**
     *
     * Caught live on the Thor, 2026-09-03, in this file's own log. The daemon had `quiet`
     * applied; a `requestCeiling` call then ran `captureOnce`, which read the QoS node and recorded:
     *
     * ```
     * captured stock cpu row: 0:1555200 1:1555200 2:1555200 3:2054400 … 7:1843200
     * ```
     *
     * Those are `quiet`'s exact values. **The active profile was recorded as stock**, so every later
     * withdrawal would faithfully restore a cap — the failure this object's own header KDoc already
     * names: *"A restore that succeeds at restoring the wrong value is worse than one that fails,
     * because nothing anywhere says so."* It was written down as a hazard and then reproduced, because
     * the capture had no check at all.
     *
     * The rule is the one [withdrawOnColdStart] already uses twenty lines up — `khz in 1 until ceiling`
     * means somebody is holding that CPU down — applied at the other end of the lifecycle.
     *
     * **Raising to the ceiling cannot overclock and cannot fight the HAL**, which is why this is the
     * safe direction to resolve the ambiguity in. This is the QoS *request* node: a ceiling requested AT
     * the ceiling restricts nothing, because the silicon named that number (`availableKhz.last()` is the
     * kernel's own published table), and the vendor HAL still composes the value actually in force. So
     * the correction only stops Mimir requesting somebody else's cap forever; it never grants more than
     * the device already offers. Real stock on the Thor is exactly this shape — the header KDoc records
     * six of eight CPUs sitting at their cluster's hardware ceiling.
     *
     * Pure and unit-tested against literal rows rather than proved by breaking a real device.
     */
    internal fun stockCpuRowWithoutHeldCaps(liveRow: String, ceilingForCpu: Map<Int, Int>): String {
        val live = parseCpuQosRow(liveRow)
        if (live.isEmpty()) return liveRow
        return live.keys.sorted().joinToString(" ") { cpuId ->
            val khz = live.getValue(cpuId)
            val ceiling = ceilingForCpu[cpuId]
            // Unknown ceiling -> leave the value alone. Guessing on a CPU whose table we could not read
            // is how a "repair" becomes a write nobody asked for.
            val held = ceiling != null && khz in 1 until ceiling
            "$cpuId:${if (held) ceiling else khz}"
        }
    }

    private fun captureOnce(policy: MimirTunerPolicy, nodes: MimirTunerNodes) {
        when (policy.kind) {
            MimirTunerPolicy.Kind.CPU -> {
                if (capturedCpuRow != null) return
                synchronized(this) {
                    if (capturedCpuRow != null) return
                    val liveRow = nodes.read(CpuQosNode)?.trim()?.takeIf { it.isNotEmpty() }
                    capturedCpuRow = liveRow?.let { row ->
                        val ceilingForCpu = discoverTunerPolicies(nodes)
                            .filter { it.kind == MimirTunerPolicy.Kind.CPU }
                            .flatMap { p -> affectedCpuIds(p, nodes).mapNotNull { id -> p.ceilingKhz?.let { id to it } } }
                            .toMap()
                        stockCpuRowWithoutHeldCaps(row, ceilingForCpu).also { corrected ->
                            if (corrected != row) {
                                Log.w(
                                    TAG,
                                    "capture: a cap was already in force, so the live row is NOT stock. " +
                                        "live=$row recorded=$corrected",
                                )
                            }
                        }
                    }
                    Log.i(TAG, "captured stock cpu row: ${capturedCpuRow ?: "(unreadable)"}")
                }
            }
            MimirTunerPolicy.Kind.GPU -> {
                if (capturedGpu != null) return
                synchronized(this) {
                    if (capturedGpu != null) return
                    val min = nodes.read("${policy.nodePath}/min_pwrlevel")?.trim()?.toIntOrNull()
                    val max = nodes.read("${policy.nodePath}/max_pwrlevel")?.trim()?.toIntOrNull()
                    capturedGpu = if (min != null && max != null) GpuBounds(min, max) else null
                    Log.i(TAG, "captured stock gpu bounds: ${capturedGpu ?: "(unreadable)"}")
                }
            }
        }
    }

    /**
     * The CPU request, addressed per CPU because the node is.
     *
     * **Every CPU in the cluster is named, and no CPU outside it is.** The node's rows are per-CPU
     * while the player's choice is per-CLUSTER, so a command that named only the policy id would leave
     * the cluster's other cores at whatever they held and produce a cluster running at two ceilings.
     * `affected_cpus` is what the kernel says belongs together, so it is what gets written.
     *
     * **Mimir never writes a MIN.** The published trap is that lowering a cluster's `scaling_min_freq`
     * wakes the HAL and can stomp a DIFFERENT cluster's cap. This axis needs no min at all -- and this
     * tree's own device notes record the opposite kernel behaviour too (clamping min down to meet max
     * rather than rejecting), which is exactly the kind of per-kernel disagreement to stay out of.
     */
    internal fun cpuCeilingCommand(
        policy: MimirTunerPolicy,
        ceilingKhz: Int,
        nodes: MimirTunerNodes = MimirTunerBackend.nodes(),
    ): String {
        val row = affectedCpuIds(policy, nodes).joinToString(" ") { "$it:$ceilingKhz" }
        return writeCommand(CpuQosNode, row)
    }

    /**
     * The Adreno request.
     *
     * **`min_pwrlevel` is widened FIRST, and skipping that is the whole bug.** The kernel clamps
     * `max_pwrlevel` so it can never name a slower level than `min_pwrlevel`, so setting a ceiling
     * while the floor sits above it silently snaps back and the surface reports a change that never
     * happened. Widen the floor to the slowest level, then set the ceiling.
     */
    internal fun gpuCeilingCommand(policy: MimirTunerPolicy, ceilingKhz: Int): String {
        val level = gpuPowerLevelForKhz(policy.availableKhz, ceilingKhz)
        val slowest = policy.availableKhz.lastIndex.coerceAtLeast(level)
        return listOf(
            writeCommand("${policy.nodePath}/min_pwrlevel", slowest.toString()),
            writeCommand("${policy.nodePath}/max_pwrlevel", level.toString()),
        ).joinToString("; ")
    }

    /**
     * One shell write.
     *
     * **No `chmod` anywhere in this file, and that is deliberate.** The published technique for holding
     * a cap chmods the sysfs node around the write and leaves it `444` so nothing else can move it. It
     * works, and it leaves the device CHANGED in a way a reboot is required to undo — a node locked
     * read-only is a worse artefact than a cap, because it is invisible and it outlives the app that
     * did it. This axis composes with the HAL rather than fighting it, so there is nothing to lock.
     *
     * **The value is never player- or adb-supplied.** Everything written here is an integer this file
     * derived from a table the kernel published; see [nearestSupportedKhz], which is the only door in.
     */
    private fun writeCommand(path: String, value: String): String = "echo '$value' > $path"

    /**
     * Snap a request onto a frequency the hardware actually has.
     *
     * **This is the only entry point for a number, and it is where safety lives.** A value not in the
     * published table is refused rather than rounded blindly: the caller gets null and nothing is
     * written. That is what makes [writeCommand]'s interpolation safe — nothing reaches it that did not
     * come out of `scaling_available_frequencies` or the Adreno's own table.
     */
    internal fun nearestSupportedKhz(policy: MimirTunerPolicy, requestedKhz: Int): Int? {
        if (policy.availableKhz.isEmpty()) return null
        if (requestedKhz <= 0) return null
        return policy.availableKhz.minByOrNull { kotlin.math.abs(it - requestedKhz) }
    }

    /** What is IN FORCE, which is a different question from what was requested. */
    internal fun readInForceKhz(policy: MimirTunerPolicy, nodes: MimirTunerNodes): Int? = when (policy.kind) {
        MimirTunerPolicy.Kind.CPU -> nodes.read("${policy.nodePath}/scaling_max_freq")?.trim()?.toIntOrNull()
        MimirTunerPolicy.Kind.GPU -> nodes.read("${policy.nodePath}/max_pwrlevel")?.trim()?.toIntOrNull()
            ?.let { gpuKhzForPowerLevel(policy.availableKhz, it) }
    }

    /** `0:2016000 1:2016000 …` → `{0 to 2016000, 1 to 2016000, …}`. */
    internal fun parseCpuQosRow(raw: String): Map<Int, Int> =
        raw.trim().split(Regex("\\s+")).mapNotNull { pair ->
            val cpu = pair.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val khz = pair.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
            cpu to khz
        }.toMap()

    /**
     * The CPUs this policy governs, from the kernel rather than from the policy id.
     *
     * Falls back to the policy id alone only when `affected_cpus` and `related_cpus` are both
     * unreadable — a single-core answer is wrong on a cluster, but it is at least an answer that
     * touches nothing it was not asked to.
     */
    internal fun affectedCpuIds(policy: MimirTunerPolicy, nodes: MimirTunerNodes = MimirTunerBackend.nodes()): List<Int> {
        val affected = nodes.read("${policy.nodePath}/affected_cpus")
            ?: nodes.read("${policy.nodePath}/related_cpus")
        val parsed = affected.orEmpty().trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
        return parsed.ifEmpty { listOf(policy.id) }
    }

    /** `INT_MAX` — the kernel's spelling of "no ceiling requested". Used only as a last-resort fallback. */
    internal const val UnlimitedKhz = Int.MAX_VALUE
}
