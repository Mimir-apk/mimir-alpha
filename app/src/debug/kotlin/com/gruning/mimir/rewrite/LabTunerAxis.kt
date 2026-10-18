package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.tweaker.TweakerLabTunerAxisContract
import java.io.File

/**
 * A synthetic CPU tuning axis, made of ordinary files in this app's own storage.
 *
 * **What it is for.** Proving that a cap set by one Mimir process is ADOPTED by the next one, exactly,
 * rather than widened away — and proving it on any device, repeatably, in a way that cannot pass by
 * accident. On the real axis that proof is not deterministic: the QoS node has a second writer
 * (`MimirTunerWrite`'s own record of CPU 7 moving within thirty minutes with no Mimir write between),
 * so a run can fail because the guard CORRECTLY discarded a record whose cap somebody else had removed.
 * That is a passing behaviour reported as a failure, which makes the whole instrument untrustworthy.
 * Here the only writer is this file.
 *
 * **What it is NOT.** It is not a substitute for the real-axis pass on hardware. It proves the
 * mechanism; it does not certify any silicon. Both are owed.
 *
 * **Where it is injected, and why that is the only defensible place.** At the DISCOVERY input —
 * [MimirTunerBackend], which supplies the default `nodes` and `host` every production call site takes.
 * Everything downstream is the real code, unmodified: `parseCpuFrequenciesKhz`, `cpuClusterLabel`,
 * `affectedCpuIds`, `captureOnce`, `nearestSupportedKhz`, `recordedSessionStillInForce`,
 * `reconcileColdStart`, the record's Binder round trip and the daemon's admission of every value in it.
 * Three cheaper seams were considered and rejected: injecting a fake `MimirTunerRecordStore` (a fake-store test would exercise an arrangement production never builds), a flag that makes admission return Admitted (the lesson of
 * `0fbb8053`: evidence that skips the protocol is worthless), and injecting a ready-made policy list
 * (which would skip discovery parsing, the capture, and the affected-cpu walk — most of what can break).
 *
 * **While armed, discovery is synthetic INSTEAD of real, not alongside it.** The QoS node is a single
 * row shared by every CPU on the device and having two writers compose on it is incoherent. The stated
 * consequence, so it can be judged rather than discovered later: an armed device does not widen a real
 * orphaned cap at cold start. That is lab-only and ends at disarm.
 *
 * **Arming is a DIRECTORY, and that is load-bearing.** The proof's shape is a process restart followed by inspection of the new process. A flag in process memory would be gone at exactly the moment
 * under test; a directory in `filesDir` survives `am force-stop` and survives the daemon being killed
 * too. `installIfArmed` runs in `onCreate` BEFORE the cold-start reconcile, which is the whole ordering
 * requirement — a seam installed afterwards would be a seam the reconcile never saw.
 *
 * This file is compiled into debuggable builds only; `app/src/release/kotlin` carries a no-op
 * [LabTunerAxisInstaller] with the same signatures, so the release APK does not contain any of it.
 */
internal object LabTunerAxisInstaller {

    private const val TAG = "MimirTuner"

    /** The armed directory, under the app's own files. Its existence is the flag. */
    internal fun axisDirectory(context: Context): File =
        File(context.filesDir, TweakerLabTunerAxisContract.LAB_AXIS_DIRECTORY_NAME)

    /**
     * Point the tuner at the synthetic axis, if and only if it has been armed.
     *
     * Silent and side-effect-free on an unarmed device, which is every device that has not been
     * explicitly told otherwise by an adb extra behind the debug-build gate.
     */
    fun installIfArmed(context: Context) {
        val directory = axisDirectory(context)
        if (!directory.isDirectory) return
        MimirTunerBackend.nodes = { LabTunerNodes(directory, RealMimirTunerNodes()) }
        val labHost = LabTunerHost(directory)
        MimirTunerBackend.host = labHost::run
        // The lab host is a file writer running as this app's own uid, so it is always present. Routing
        // this is what lets the proof run on a handheld with no vendor performance server at all.
        MimirTunerBackend.hostAvailable = { true }
        Log.i(TAG, "lab tuner axis armed at ${directory.absolutePath}")
    }

    /**
     * Create and seed the axis, then install it.
     *
     * **Idempotent, and deliberately refuses to re-seed.** Re-running the arm extra against an axis that
     * is already in use must not reset the QoS file — that would silently destroy the cap the proof is
     * in the middle of measuring and turn a real adoption failure into a "discarded" that looks like the
     * guard working.
     */
    fun arm(context: Context) {
        val directory = axisDirectory(context)
        if (directory.isDirectory) {
            Log.i(TAG, "lab tuner axis already armed; leaving its state alone")
        } else {
            seed(directory)
            Log.i(TAG, "lab tuner axis seeded at ${directory.absolutePath}")
        }
        installIfArmed(context)
    }

    /** Delete the axis and put the real device back. */
    fun disarm(context: Context) {
        val directory = axisDirectory(context)
        val removed = directory.deleteRecursively()
        MimirTunerBackend.nodes = { RealMimirTunerNodes() }
        MimirTunerBackend.host = PServerHost::run
        MimirTunerBackend.hostAvailable = PServerHost::available
        Log.i(TAG, "lab tuner axis disarmed removed=$removed")
    }

    /**
     * Write the axis's starting state.
     *
     * **Stock is `INT_MAX` on both cpus, and that choice is the proof's oracle.** It makes a cap
     * (`1800000`), an exact-stock restore (`2147483647`) and a widen-to-ceiling (`2400000`) three
     * DISTINCT contents of one file, so a single `cat` distinguishes adopted from withdrawn from widened
     * from discarded without reading a single log line. `INT_MAX` is also explicitly legal on every cpu
     * in the daemon's admission, so the record built from it crosses the Binder unrefused.
     */
    internal fun seed(directory: File) {
        val policy = File(directory, TweakerLabTunerAxisContract.LAB_POLICY_DIRECTORY_NAME)
        policy.mkdirs()
        File(policy, "scaling_available_frequencies")
            .writeText(TweakerLabTunerAxisContract.LAB_AVAILABLE_FREQUENCIES_ROW + "\n")
        File(policy, "affected_cpus").writeText(TweakerLabTunerAxisContract.LAB_AFFECTED_CPUS_ROW + "\n")
        File(directory, TweakerLabTunerAxisContract.LAB_STOCK_ROW_FILE_NAME)
            .writeText(TweakerLabTunerAxisContract.LAB_STOCK_ROW + "\n")
        File(directory, TweakerLabTunerAxisContract.LAB_QOS_FILE_NAME)
            .writeText(TweakerLabTunerAxisContract.LAB_STOCK_ROW + "\n")
        // Through the same reflection the host uses, so the in-force file can never start out saying
        // something the request file does not support.
        reflectLabInForce(directory, TweakerLabTunerAxisContract.LAB_STOCK_ROW)
    }
}

/**
 * The synthetic axis as [MimirTunerNodes] sees it.
 *
 * **Delegation is the interesting half.** Only three things are answered locally: the policy listing,
 * the QoS request node, and anything already inside the lab directory. The Adreno prefix answers null,
 * because the synthetic axis is CPU-only and a half-present GPU would make `withdrawOnColdStart` reason
 * about a device that does not exist. Everything else — most importantly `/proc/sys/kernel/random/boot_id`,
 * which is what stops a record being adopted across a reboot — goes to the REAL nodes. Faking the boot
 * identity would have quietly disabled the one guard that a lab axis cannot otherwise exercise.
 */
internal class LabTunerNodes(
    private val directory: File,
    private val delegate: MimirTunerNodes,
) : MimirTunerNodes {

    private val policyDirectory =
        File(directory, TweakerLabTunerAxisContract.LAB_POLICY_DIRECTORY_NAME)

    override fun listCpuPolicyDirectories(): List<String> = listOf(policyDirectory.absolutePath)

    override fun read(path: String): String? = when {
        path == MimirTunerWrite.CpuQosNode -> readFile(labQosFile(directory))
        path.startsWith(directory.absolutePath) -> readFile(File(path))
        // No GPU axis while armed. Answering null here is what makes discoverTunerPolicies drop the
        // Adreno row entirely, rather than publishing one whose nodes nothing can write.
        path.startsWith(MimirTunerWrite.GpuRoot) -> null
        else -> delegate.read(path)
    }

    private fun readFile(file: File): String? = runCatching { file.readText() }.getOrNull()
}

/**
 * The privileged host, replaced by a file writer with no privilege at all.
 *
 * **It validates the command shape rather than trusting it, and that is not ceremony.** This stands
 * where `PServerHost` stands, so the discipline that path lives under has to hold here too: exactly one
 * command form is accepted, its row must be `cpuId:kHz` pairs and nothing else, and the single path
 * literal it may name is rewritten into the lab directory. Anything else — a GPU command, a compound
 * statement, a path this axis does not own — is refused. A lab host that executed whatever it was handed
 * would be a worse thing to have in a debug build than the axis is worth.
 *
 * **The shell is real, and the exec is deliberately not unit-tested.** `PServerHost` hands a shell
 * command to another process, so the lab host runs one too: the redirect, the quoting and the truncating
 * write are the kernel's and the shell's semantics, not Kotlin's, and re-implementing them with
 * `File.writeText` would be testing a different thing from what runs on the device. [exec] is injectable
 * so the JVM tests can pin the pure halves — shape validation and path rewriting — without needing
 * `/system/bin/sh` to exist on a build machine.
 */
internal class LabTunerHost(
    private val directory: File,
    private val exec: (String) -> Boolean = ::execViaSystemShell,
) {

    fun run(command: String): Boolean {
        val row = acceptedRow(command) ?: run {
            Log.i("MimirTuner", "lab host refused a command it does not own")
            return false
        }
        if (!exec(rewrite(row))) return false
        reflectLabInForce(directory, row)
        return true
    }

    /** The row a command is asking for, or null when this host does not own the command. */
    internal fun acceptedRow(command: String): String? =
        AcceptedCommand.matchEntire(command.trim())?.groupValues?.get(1)

    /** The same write, addressed to the lab axis instead of the kernel. */
    internal fun rewrite(row: String): String =
        "echo '$row' > ${labQosFile(directory).absolutePath}"

    internal companion object {
        /**
         * The one command form this host executes.
         *
         * Anchored end to end, so a compound statement cannot smuggle a second write past the shape
         * check by appending it — which is exactly how the GPU command is built elsewhere in this tree.
         */
        internal val AcceptedCommand: Regex = Regex(
            "^echo '(\\d+:\\d+(?: \\d+:\\d+)*)' > " + Regex.escape(MimirTunerWrite.CpuQosNode) + "$",
        )
    }
}

/** The file standing in for the kernel's QoS request node. */
private fun labQosFile(directory: File): File =
    File(directory, TweakerLabTunerAxisContract.LAB_QOS_FILE_NAME)

/**
 * What the kernel would report as IN FORCE after a request, written where `scaling_max_freq` lives.
 *
 * **The two nodes answer different questions and the axis has to keep them different.** The QoS node
 * says what was REQUESTED; `scaling_max_freq` says what is in force after the hardware's own ceiling has
 * had its say. Collapsing them would make the axis agree with a surface that read back its own request,
 * which is the exact lie `MimirTunerWrite` is written to avoid — so the request is clamped to the
 * published ceiling here, and a request of `INT_MAX` lands as the ceiling rather than as `INT_MAX`.
 */
internal fun reflectLabInForce(directory: File, row: String) {
    val requested = MimirTunerWrite.parseCpuQosRow(row)[TweakerLabTunerAxisContract.LAB_POLICY_ID] ?: return
    val inForce = minOf(requested, TweakerLabTunerAxisContract.LAB_CEILING_KHZ)
    val policy = File(directory, TweakerLabTunerAxisContract.LAB_POLICY_DIRECTORY_NAME)
    policy.mkdirs()
    File(policy, "scaling_max_freq").writeText("$inForce\n")
}

private fun execViaSystemShell(command: String): Boolean = runCatching {
    ProcessBuilder("/system/bin/sh", "-c", command)
        .redirectErrorStream(true)
        .start()
        .waitFor() == 0
}.getOrDefault(false)
