package com.gruning.mimir.tweaker

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * What a volatile ceiling read **before Mimir first clamped it in this boot** -- the value a restore
 * has to write back to put the device down at stock.
 *
 * ## Why this exists: the Thor, 2026-09-19
 *
 * Quiet applied at 18:16 and held. Around 20:21 Android's cached-app freezer froze the daemon
 * (`sync unfroze 18152` on resume); the session lease was gone when the app came back, but **the
 * hardware was still carrying Quiet's ceilings**, because nothing had restored them. The bridge saw
 * the mismatch and re-armed the standing profile:
 *
 * ```
 * 20:21:52.889  session=Stock
 * 20:21:52.890  rearmwatch observe decision=REARM standing=quiet effective=stock availability=READY
 * 20:22:15.241  SESSION lease scope=SYSTEM generation=3 transactionId=d5ed59f2-...
 * ```
 *
 * That re-arm captured the live nodes as its stock snapshot. They were Quiet's numbers. From that
 * moment `originalValue` -- the restore point -- **was the clamp itself**, and every later "Stock"
 * faithfully wrote the clamp back and confirmed it:
 *
 * ```
 * 22:02:30.836  requested=stock prior=quiet result=Stock
 * 22:02:42.124  requested=balanced prior=stock result=Rejected(code=CPU_TARGET_ABOVE_LIVE_CEILING)
 * 22:02:39.525  cpu policy 0 target 1113600 would raise settled live ceiling 1017600
 *               (live window 1017600..1017600); settledFloors={0=1017600, 3=1651200, 7=1843200}
 * ```
 *
 * Read from the device 50 minutes after that "successful" Stock, with `adb shell cat`:
 *
 * ```
 * /sys/kernel/msm_performance/parameters/cpu_max_freq
 *   0:1017600 1:1017600 2:1017600 3:1651200 4:1651200 5:1651200 6:1651200 7:1843200
 * ```
 *
 * Quiet's plan exactly, against a stock `cpuinfo_max_freq` of 2016000/2803200/3187200. The little
 * cluster sat at 1.0 GHz. **The dead end closes on itself**: the admission reads the live ceiling to
 * decide what may be admitted, so a clamp Mimir left behind becomes the evidence that forbids
 * lifting it, and the kernel drags `scaling_min_freq` down to meet the ceiling we wrote, which is
 * why `settledFloors` reads back those same numbers. Nothing the player can choose escapes it --
 * Stock included. Only a reboot did.
 *
 * ## The rule
 *
 * **The first capture in a boot defines stock for that boot; no later capture may redefine it.**
 * Volatile axes are reinitialised by the kernel at boot ([governs]), so one baseline per boot is
 * both sufficient and complete -- the same reasoning the cross-boot restore filter uses to let a cross-boot
 * restore skip them. Keying on the boot identity is what makes a stale baseline impossible: a new
 * boot discards the old record wholesale rather than merging with it.
 *
 * ## What this deliberately does NOT do
 *
 * It does not try to name stock from the hardware. `cpuinfo_max_freq` looks like the answer and is
 * not: on this chassis 3187200 and 2803200 are BOOST bins, present in `cpuinfo_max_freq` and absent
 * from `scaling_available_frequencies`, and writing them fails (measured 2026-09-01). The only
 * trustworthy stock value is the one observed before Mimir touched the node, which is what this
 * remembers.
 *
 * It cannot help a boot whose FIRST capture already finds the hardware clamped -- by a previous
 * install, or by this defect before the fix shipped. There is no evidence left on the device to
 * recover the true value from, and inventing one would be worse than refusing. [poisonedNodes]
 * names that case so the log says it plainly instead of the device going quiet at 1.0 GHz.
 */
internal object StockCeilingBaseline {
    /**
     * The axes a per-boot baseline governs. FAN is excluded because it is a persistent `settings`
     * value that survives a reboot: its restore point is already durable and is confirmed on the
     * cross-boot path, so a boot-keyed record would be the wrong shape for it.
     */
    fun governs(axis: TuningAxis): Boolean = axis == TuningAxis.CPU || axis == TuningAxis.GPU

    /**
     * Nodes whose captured value sits below the device's own stock ceiling -- the signature of a
     * capture taken while a clamp was live. Reported per policy as `logicalId observed<stock`.
     */
    fun poisonedNodes(
        observed: Map<String, Int>,
        stockCeilingsKhz: Map<String, Int>,
    ): List<String> = observed.toSortedMap().mapNotNull { (logicalId, value) ->
        val stock = stockCeilingsKhz[logicalId] ?: return@mapNotNull null
        if (value < stock) "$logicalId $value<$stock" else null
    }
}

/** One boot's remembered stock values, keyed by logical ID. */
internal data class StockCeilingBaselineRecord(
    val bootIdentity: String,
    val values: Map<String, Int>,
)

/**
 * Pure merge, so the first-write-wins rule can be proved without a filesystem.
 *
 * First write wins **per logical ID**, not per record: a boot whose first transaction tuned only
 * the CPU must still be able to learn the GPU's stock value when a later transaction first touches
 * it. Only a value already remembered for this boot is immovable.
 */
internal object StockCeilingBaselinePolicy {
    fun merge(
        stored: StockCeilingBaselineRecord?,
        bootIdentity: String,
        observed: Map<String, Int>,
    ): StockCeilingBaselineRecord {
        val carried = stored?.takeIf { it.bootIdentity == bootIdentity }?.values.orEmpty()
        val merged = LinkedHashMap<String, Int>(carried)
        observed.forEach { (logicalId, value) -> merged.putIfAbsent(logicalId, value) }
        return StockCeilingBaselineRecord(bootIdentity, merged.toSortedMap().toMap())
    }
}

/** Canonical JSON so a record written by one process reads identically in the other. */
internal object StockCeilingBaselineCodec {
    private const val KEY_SCHEMA = "schema"
    private const val KEY_BOOT = "boot"
    private const val KEY_VALUES = "values"
    private const val SCHEMA = 1

    fun encode(record: StockCeilingBaselineRecord): ByteArray {
        val values = JSONObject()
        record.values.toSortedMap().forEach { (logicalId, value) -> values.put(logicalId, value) }
        return JSONObject()
            .put(KEY_SCHEMA, SCHEMA)
            .put(KEY_BOOT, record.bootIdentity)
            .put(KEY_VALUES, values)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray?): StockCeilingBaselineRecord? = runCatching {
        val json = JSONObject(String(bytes ?: return null, StandardCharsets.UTF_8))
        if (json.optInt(KEY_SCHEMA) != SCHEMA) return null
        val boot = json.optString(KEY_BOOT).takeIf { it.isNotBlank() } ?: return null
        val values = json.optJSONObject(KEY_VALUES) ?: return null
        val decoded = LinkedHashMap<String, Int>()
        values.keys().forEach { logicalId ->
            val value = values.opt(logicalId) as? Int ?: return null
            decoded[logicalId] = value
        }
        StockCeilingBaselineRecord(boot, decoded.toSortedMap().toMap())
    }.getOrNull()
}

/**
 * Device-protected, atomically replaced, and read by both the daemon and the guardian process.
 *
 * Device-protected rather than credential-protected because a restore may have to run before the
 * user has unlocked -- the same reason the guardian's own stores live there.
 */
internal class StockCeilingBaselineStore(directory: File) {
    private val root = directory.absoluteFile
    private val record = File(root, RECORD_NAME)
    private val staging = File(root, ".$RECORD_NAME.tmp")

    init { require(root.isAbsolute) }

    @Synchronized
    fun load(): StockCeilingBaselineRecord? =
        runCatching { StockCeilingBaselineCodec.decode(record.takeIf(File::isFile)?.readBytes()) }
            .getOrNull()

    /**
     * Fold this capture's observations into the boot's baseline and answer the authoritative one.
     *
     * Answers the merged record even when persisting fails, so a read-only or full filesystem
     * degrades to today's behaviour for the next transaction rather than failing this one: the
     * capture that is in flight still gets a correct restore point from what it just observed.
     */
    @Synchronized
    fun remember(
        bootIdentity: String,
        observed: Map<String, Int>,
    ): StockCeilingBaselineRecord {
        val merged = StockCeilingBaselinePolicy.merge(load(), bootIdentity, observed)
        runCatching {
            if (!root.exists() && !root.mkdirs()) return merged
            if (staging.exists() && !staging.delete()) return merged
            FileOutputStream(staging).use { output ->
                output.write(StockCeilingBaselineCodec.encode(merged))
                output.flush()
                output.fd.sync()
            }
            Files.move(
                staging.toPath(),
                record.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return merged
    }

    companion object {
        const val DIRECTORY = "stock_ceiling_baseline"
        private const val RECORD_NAME = "baseline.json"
    }
}
