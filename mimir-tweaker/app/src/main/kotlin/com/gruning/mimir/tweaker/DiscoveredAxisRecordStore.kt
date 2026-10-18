package com.gruning.mimir.tweaker

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * The discovered tuner's session, remembered by the daemon so it outlives the app that set it.
 *
 * **Why this exists .** Mimir's discovered tuner holds its captured stock
 * value in process memory. The CAP survives Mimir being killed — it is a privileged sysfs write and
 * nothing in Mimir undoes it on death — but the capture does not, so a killed Mimir leaves a cap in
 * force with nothing left that knows what to put back. The ruling moved the RECORD here and left the
 * WRITE where it was; this file is the record, and nothing in it writes hardware.
 *
 * **It carries the whole session, not just the stock.** The QoS node has a second writer (observed on
 * the Thor: CPU 7 at `INT_MAX` at 16:44 and `2956800` at 17:14 with no Mimir write between), so a
 * correct restore must know what Mimir itself asked for and put back only the CPUs still holding it.
 * A record carrying stock alone would come back after a kill as a blind stamp over whatever the other
 * writer had done since.
 *
 * **No sysfs path is stored, and the shape is why.** The record is fixed to the two axes the
 * discovered tuner knows. A path-shaped record would let an unprivileged caller choose what this
 * privileged process writes down and later hands back for writing — the escalation this contract has
 * refused since v6. Values are admitted against [DiscoveredAxisAdmission], which is this process's own
 * hardware discovery, so a record naming a CPU or a frequency the silicon does not publish is refused.
 *
 * **Authenticated storage was considered and deliberately not used.** The signed journal and the
 * Guardian arm record authorise mutations, so tampering with them buys a write; this record authorises
 * nothing. It is a memo whose every value is re-admitted here on the way in and snapped onto the
 * kernel's own published table by `MimirTunerWrite.nearestSupportedKhz` on the way out, and an actor
 * who could rewrite a file in this app's device-protected storage could write the sysfs node directly
 * for less effort. It is device-protected so a locked device can still answer at boot, and
 * [AtomicFile] so a torn write is a missing record rather than a corrupt one.
 */
internal data class DiscoveredAxisRecord(
    val bootIdentity: String,
    val cpuStockRow: String,
    val cpuRequestedRow: String,
    val gpuStockMinLevel: Int = ABSENT_LEVEL,
    val gpuStockMaxLevel: Int = ABSENT_LEVEL,
    val gpuRequestedMinLevel: Int = ABSENT_LEVEL,
    val gpuRequestedMaxLevel: Int = ABSENT_LEVEL,
    /**
     * The v10 ceiling's durable PENDING-DELETE (spent) marker. True means a withdraw already restored the hardware and
     * read it back at stock, and only this record's DELETE is owed. The stock and requested data are KEPT beside the
     * marker (the way back is never discarded before its deletion is confirmed), but the record is no longer a held
     * ceiling: nothing may re-assert it, capture from it or answer HELD from it, in this process or after a restart.
     */
    val pendingDelete: Boolean = false,
) {
    companion object {
        /** `-1`: this record names no GPU session. Absence, never a level. */
        const val ABSENT_LEVEL = -1
    }
}

/**
 * What this process discovered about its own hardware, as the record store needs it.
 *
 * Deliberately a narrow port rather than a `HardwareCapabilities` reference: admission needs the CPU
 * ids with their published frequency sets and the GPU's power-level count, and nothing else. A port
 * this small is also a port a unit test can implement honestly.
 */
internal interface DiscoveredAxisAdmission {
    /** CPU id → the frequencies that CPU's policy actually publishes. */
    fun cpuFrequenciesKhz(): Map<Int, Set<Int>>

    /** How many Adreno power levels this process enumerated; 0 when there is no GPU axis. */
    fun gpuPowerLevelCount(): Int
}

/** Why a record was refused. Named, because "false" is not a reason. */
internal sealed interface DiscoveredAxisAdmissionResult {
    data object Admitted : DiscoveredAxisAdmissionResult
    data class Refused(val code: String, val detail: String) : DiscoveredAxisAdmissionResult
}

internal object DiscoveredAxisAdmissionCheck {

    /** `INT_MAX` is the kernel's spelling of "no ceiling requested" and is legal on every CPU. */
    private const val UNLIMITED_KHZ = Int.MAX_VALUE

    fun admit(
        record: DiscoveredAxisRecord,
        discovery: DiscoveredAxisAdmission,
    ): DiscoveredAxisAdmissionResult {
        if (record.bootIdentity.isBlank()) {
            return DiscoveredAxisAdmissionResult.Refused("NO_BOOT_IDENTITY", "a record must name its boot")
        }
        val published = discovery.cpuFrequenciesKhz()
        listOf("stock" to record.cpuStockRow, "requested" to record.cpuRequestedRow).forEach { (which, raw) ->
            if (raw.isBlank()) return@forEach
            val parsed = parseRow(raw)
                ?: return DiscoveredAxisAdmissionResult.Refused("MALFORMED_ROW", "$which row does not parse")
            parsed.forEach { (cpuId, khz) ->
                val opp = published[cpuId]
                    ?: return DiscoveredAxisAdmissionResult.Refused(
                        "UNDISCOVERED_CPU",
                        "cpu $cpuId is not one this device published",
                    )
                if (khz != UNLIMITED_KHZ && khz !in opp) {
                    return DiscoveredAxisAdmissionResult.Refused(
                        "UNDISCOVERED_FREQUENCY",
                        "cpu $cpuId does not publish $khz kHz",
                    )
                }
            }
        }
        val levels = discovery.gpuPowerLevelCount()
        val gpuValues = listOf(
            record.gpuStockMinLevel,
            record.gpuStockMaxLevel,
            record.gpuRequestedMinLevel,
            record.gpuRequestedMaxLevel,
        )
        gpuValues.forEach { level ->
            if (level == DiscoveredAxisRecord.ABSENT_LEVEL) return@forEach
            if (levels <= 0) {
                return DiscoveredAxisAdmissionResult.Refused(
                    "NO_GPU_AXIS",
                    "this device published no GPU power levels",
                )
            }
            if (level < 0 || level >= levels) {
                return DiscoveredAxisAdmissionResult.Refused(
                    "UNDISCOVERED_POWER_LEVEL",
                    "power level $level is outside the $levels this device published",
                )
            }
        }
        return DiscoveredAxisAdmissionResult.Admitted
    }

    /** `0:2016000 1:2016000 …` → `{0 to 2016000, …}`; null when any pair is malformed. */
    fun parseRow(raw: String): Map<Int, Int>? {
        val pairs = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (pairs.isEmpty()) return null
        val out = mutableMapOf<Int, Int>()
        pairs.forEach { pair ->
            val cpu = pair.substringBefore(':').toIntOrNull() ?: return null
            val khz = pair.substringAfter(':', "").toIntOrNull() ?: return null
            if (khz <= 0) return null
            out[cpu] = khz
        }
        return out
    }
}

/** What reading the record's file yielded; see [DiscoveredAxisRecordStore.readState]. */
internal sealed interface DiscoveredAxisRecordRead {
    /** No record is stored. */
    data object Absent : DiscoveredAxisRecordRead

    data class Present(val record: DiscoveredAxisRecord) : DiscoveredAxisRecordRead

    /** Something is stored and it could not be read, decoded or admitted: the caller must not assume nothing is held. */
    data class Unreadable(val detail: String) : DiscoveredAxisRecordRead
}

/** The record's durable home. */
internal class DiscoveredAxisRecordStore(
    private val file: AtomicFile,
    private val discovery: DiscoveredAxisAdmission,
) {

    /** Admit first, then persist. A refused record is never written and never partially written. */
    fun write(record: DiscoveredAxisRecord): DiscoveredAxisAdmissionResult {
        val verdict = DiscoveredAxisAdmissionCheck.admit(record, discovery)
        if (verdict is DiscoveredAxisAdmissionResult.Refused) return verdict
        synchronized(PROCESS_LOCK) {
            val stream = file.startWrite()
            try {
                stream.write(encode(record).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
        return DiscoveredAxisAdmissionResult.Admitted
    }

    /**
     * The record, or null.
     *
     * **A record that no longer passes admission is dropped rather than served.** The hardware this
     * process can see is the only thing that makes a stored value meaningful, and a stored value the
     * device no longer publishes describes a machine that is not this one.
     */
    fun read(): DiscoveredAxisRecord? = (readState() as? DiscoveredAxisRecordRead.Present)?.record

    /**
     * [read] with the three answers kept apart: nothing stored, a record, or something stored that could not be used.
     *
     * [read] collapses the last two failure shapes into the first, which is right for the v9 memo (it only ever asks
     * "is there a record I may serve") and wrong for the v10 ceiling, whose record is the only way back from a cap that
     * may be in force: there, "I could not read it" must never be taken for "nothing is held". An I/O failure, a record
     * that does not decode, and one that no longer passes admission are all [DiscoveredAxisRecordRead.Unreadable].
     */
    fun readState(): DiscoveredAxisRecordRead = synchronized(PROCESS_LOCK) {
        val bytes = try {
            file.readFully()
        } catch (error: Throwable) {
            // AtomicFile reports a missing file as an I/O failure too; the file's own existence is what tells them apart.
            return if (!fileExists()) {
                DiscoveredAxisRecordRead.Absent
            } else {
                DiscoveredAxisRecordRead.Unreadable("read failed: ${error.javaClass.simpleName}")
            }
        }
        // KNOWN LIMIT: a record that stays unreadable or inadmissible is never cleaned up by the ceiling's own paths (no
        // cleanup without a readable record), so apply stays STORE_UNAVAILABLE until it is repaired or removed by the owner.
        val record = try {
            decode(String(bytes, Charsets.UTF_8))
        } catch (error: Throwable) {
            return DiscoveredAxisRecordRead.Unreadable("decode failed: ${error.javaClass.simpleName}")
        } ?: return DiscoveredAxisRecordRead.Unreadable("the record is not a schema this build reads")
        val verdict = try {
            DiscoveredAxisAdmissionCheck.admit(record, discovery)
        } catch (error: Throwable) {
            return DiscoveredAxisRecordRead.Unreadable("admission failed: ${error.javaClass.simpleName}")
        }
        if (verdict is DiscoveredAxisAdmissionResult.Refused) {
            return DiscoveredAxisRecordRead.Unreadable("admission refused: ${verdict.code}")
        }
        DiscoveredAxisRecordRead.Present(record)
    }

    private fun fileExists(): Boolean = try {
        file.baseFile.exists()
    } catch (_: Throwable) {
        true // cannot tell: assume it is there, which keeps a record rather than losing one
    }

    /**
     * Delete the record, and say whether it is actually gone. This used to answer `true` whatever happened, so a delete
     * that failed (read-only storage, a busy file) looked like a success and a restore was reported complete beside a
     * record that still said "held". The answer is now the file's own absence, and a delete that throws, or a file whose
     * existence cannot be established, is `false`.
     */
    fun clear(): Boolean = synchronized(PROCESS_LOCK) {
        try {
            file.delete()
        } catch (_: Throwable) {
            return false
        }
        !fileExists()
    }

    companion object {
        const val RECORD_FILE = "mimir_discovered_axis_session.v1.json"

        /**
         * The v10 ceiling's own file, deliberately NOT [RECORD_FILE].
         *
         * The two records have the same SHAPE and completely different OWNERSHIP. A v9 record is a
         * memo the frontend asked this daemon to keep about a cap the FRONTEND wrote; a v10 record
         * says this daemon itself wrote a cap, and it is the only thing that knows the way back.
         * Both can exist at once during the transition — an older frontend, or a device where the
         * v10 route was not taken — and sharing one file would let each family's `clear()` delete
         * the other's restore memory, or let `withdrawDiscoveredCeilingV10` give back a session the
         * frontend also believes it owns and is about to restore itself. One file per owner.
         *
         * Same [AtomicFile] on the same device-protected storage, same [DiscoveredAxisAdmission],
         * same encode/decode: only the filename differs.
         */
        const val CEILING_RECORD_FILE = "mimir_discovered_ceiling.v1.json"

        /** Every component here shares one process; serialize the file the way the journal does. */
        private val PROCESS_LOCK = Any()

        fun create(context: Context, discovery: DiscoveredAxisAdmission): DiscoveredAxisRecordStore =
            create(context, discovery, RECORD_FILE)

        /** The v10 daemon-written ceiling's store. See [CEILING_RECORD_FILE] for why it is separate. */
        fun createCeiling(context: Context, discovery: DiscoveredAxisAdmission): DiscoveredAxisRecordStore =
            create(context, discovery, CEILING_RECORD_FILE)

        private fun create(
            context: Context,
            discovery: DiscoveredAxisAdmission,
            fileName: String,
        ): DiscoveredAxisRecordStore {
            // Device-protected, never credential storage: a record that could not be read until first
            // unlock would be unreadable at exactly the moment a cold start needs it.
            val protectedContext = context.applicationContext.createDeviceProtectedStorageContext()
            val directory = checkNotNull(protectedContext.filesDir) { "Device-protected filesDir unavailable" }
            return DiscoveredAxisRecordStore(AtomicFile(File(directory, fileName)), discovery)
        }

        fun encode(record: DiscoveredAxisRecord): String = JSONObject().apply {
            put("schema", 1)
            put("bootIdentity", record.bootIdentity)
            put("cpuStockRow", record.cpuStockRow)
            put("cpuRequestedRow", record.cpuRequestedRow)
            put("gpuStockMinLevel", record.gpuStockMinLevel)
            put("gpuStockMaxLevel", record.gpuStockMaxLevel)
            put("gpuRequestedMinLevel", record.gpuRequestedMinLevel)
            put("gpuRequestedMaxLevel", record.gpuRequestedMaxLevel)
            // Written only when set, so every record that is not pending delete keeps its exact earlier shape.
            if (record.pendingDelete) put("pendingDelete", true)
        }.toString()

        fun decode(raw: String): DiscoveredAxisRecord? {
            val json = JSONObject(raw)
            if (json.optInt("schema", 0) != 1) return null
            val boot = json.optString("bootIdentity")
            if (boot.isNullOrBlank()) return null
            // STRICT: a missing marker is false (every earlier record, including a legacy empty one, stays readable), a JSON
            // boolean is its value, and anything else present (null, a number, any string) makes the WHOLE record
            // unreadable. optBoolean would coerce "true" to true and null/1/"yes" to false, and a false silently resumes a
            // restored record as a live ceiling.
            val pendingDelete = if (!json.has("pendingDelete")) {
                false
            } else {
                json.opt("pendingDelete") as? Boolean ?: return null
            }
            return DiscoveredAxisRecord(
                bootIdentity = boot,
                cpuStockRow = json.optString("cpuStockRow"),
                cpuRequestedRow = json.optString("cpuRequestedRow"),
                gpuStockMinLevel = json.optInt("gpuStockMinLevel", DiscoveredAxisRecord.ABSENT_LEVEL),
                gpuStockMaxLevel = json.optInt("gpuStockMaxLevel", DiscoveredAxisRecord.ABSENT_LEVEL),
                gpuRequestedMinLevel = json.optInt("gpuRequestedMinLevel", DiscoveredAxisRecord.ABSENT_LEVEL),
                gpuRequestedMaxLevel = json.optInt("gpuRequestedMaxLevel", DiscoveredAxisRecord.ABSENT_LEVEL),
                pendingDelete = pendingDelete,
            )
        }
    }
}
