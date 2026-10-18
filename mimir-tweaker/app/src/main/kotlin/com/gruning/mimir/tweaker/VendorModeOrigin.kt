package com.gruning.mimir.tweaker

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * The player's own `performance_mode`, remembered so Mimir can put it back -- and so Mimir can tell
 * its own write from the player's.
 *
 * ## Why the ordinary snapshot cannot hold this (the 2026-09-19 trap, for a setting)
 *
 * [StockCeilingBaseline] documents how a re-arm after a frozen daemon captured the live clamp --
 * Quiet's own ceilings -- as "stock", and every later restore faithfully wrote the clamp back. The
 * vendor mode has exactly that failure available, and a worse one: it is a persistent `settings`
 * value. A daemon frozen while Performance (2) is applied, then re-armed, would read `2` as the
 * stock to restore and hold it forever.
 *
 * [StockCeilingBaseline] cannot be the answer. It is boot-keyed ("the first capture in a boot
 * defines stock"), which is right for values the kernel reinitialises at boot and is why it
 * deliberately excludes FAN. `performance_mode` survives a reboot.
 *
 * ## Why this record is persistent and NOT boot-keyed
 *
 * A boot-keyed record is discarded wholesale by a new boot. That is the exact moment this one is
 * needed: a reboot mid-session leaves the key at Mimir's value, and the cross-boot replay (the
 * Guardian's persistent-axis scope) has to find the pre-Mimir mode on the far side of it. A reboot
 * is not an event that changes what the player's own mode was, so nothing here keys on boot.
 * The record dies only by the three rules below.
 *
 * ## The rules (D3 of the 2026-10-13 vendor-mode design)
 *
 * 1. **Written** at the first apply that changes the key while no record is held:
 *    `{origin = the live mode before the write, applied = the value written, transactionId}`.
 * 2. **On a later capture** ([VendorModeOriginPolicy.resolveAtCapture]): `live == applied` means
 *    nothing but Mimir has touched the key, so the origin stays and is what the new snapshot must
 *    carry (NOT the live read, which is Mimir's own value). `live != applied` means the player moved
 *    it; the record is retired and the live value becomes the origin.
 * 3. **Cleared** by a restore unless the restore did not land ([VendorModeOriginPolicy.recordAfterRestore]):
 *    the record survives only while the readback is unreadable or still Mimir's own `applied` value,
 *    because only then does the next attempt still need the origin. Any other readback -- the origin
 *    itself, or a mode the player chose -- clears it, so Mimir's `applied` can never outlive the session
 *    that wrote it and later be mistaken for the player's mode. (Also dropped when an apply targets the
 *    origin itself, since Mimir then holds no deviation to guard.)
 *
 * ## What the callers must do (vmode-ports / vmode-guardian lanes)
 *
 * - **Persist before you write the key** ([VendorModeOriginStore.save] returns true only once the
 *   record is durable) and clear only after the restore readback. A crash between the two then
 *   leaves a record that rule 2 retires harmlessly, never a written key with no record.
 * - A `false` from [VendorModeOriginStore.save] means the origin is not durable; the honest
 *   response is to leave the key alone for this transaction (the player's mode is not Mimir's to
 *   risk), which is the caller's decision, not this file's.
 * - [VendorModeOriginLoad.Corrupt] is treated by the policy as no record. That silently degrades to
 *   the pre-record behaviour for one transaction, so the caller should log it.
 * - [transactionId] is audit only: the transaction that last wrote [applied]. Restore decisions
 *   never depend on it.
 *
 * ## Known, accepted edge
 *
 * If the player moves the key mid-session and lands exactly on `applied` by hand before the restore
 * reads it, that is indistinguishable from Mimir's own write, so the restore writes the origin. The
 * cost is one restore the player may not want; it needs the player to pick Mimir's exact value while
 * the session is still running. A player choice at any other value is left alone AND clears the
 * record at that restore (the 2026-10-13 review's V-P1: a record kept across a skip let a later
 * session take Mimir's own value for the player's, or write the old origin over their newer choice).
 */
internal data class VendorModeOriginRecord(
    /** The player's mode before Mimir first changed the key; what a restore writes back. */
    val origin: Int,
    /** The mode Mimir last wrote; the only live value at which a restore may write [origin]. */
    val applied: Int,
    /** The transaction that last wrote [applied]. Audit only. */
    val transactionId: String,
) {
    init {
        require(origin in TuningPlanner.KNOWN_VENDOR_MODES) { "origin $origin is not a known vendor mode" }
        require(applied in TuningPlanner.KNOWN_VENDOR_MODES) { "applied $applied is not a known vendor mode" }
        require(origin != applied) { "a record holds a deviation; origin == applied is no record" }
        require(transactionId.isNotBlank()) { "transactionId is blank" }
    }
}

/** The origin a capture must carry, and what became of any stored record. */
internal data class VendorModeOriginResolution(
    /**
     * The mode to snapshot as the restore point: the stored origin when `live == applied`,
     * otherwise the live read. Never blindly the live read.
     */
    val origin: Int,
    /** The stored record, still valid (`live == applied`), or null when none is held. */
    val held: VendorModeOriginRecord?,
    /** The stored record superseded because the key no longer reads [VendorModeOriginRecord.applied]. */
    val retired: VendorModeOriginRecord?,
)

/** What a restore does with the vendor mode, given the live read. */
internal enum class VendorModeRestoreAction {
    /** `live == applied`: Mimir's value is still there, so write the origin back. */
    WRITE_ORIGIN,

    /** `live == origin`: nothing to do (an earlier restore, or the player, already put it there). */
    ALREADY_AT_ORIGIN,

    /**
     * `live` is neither: the player changed the mode and their choice stands. The restore reports the
     * axis `CONFIRMED` with a named detail and writes nothing.
     */
    SKIP_PLAYER_CHANGED,

    /** The key could not be read; the caller decides (it must not claim either of the others). */
    UNREADABLE,
}

/** Pure transitions, no IO, so both the daemon and the Guardian can call them and tests can pin them. */
internal object VendorModeOriginPolicy {
    /**
     * Capture time (before the apply writes anything). [stored] is whatever the store holds,
     * [live] the key as read now.
     */
    fun resolveAtCapture(stored: VendorModeOriginRecord?, live: Int): VendorModeOriginResolution =
        if (stored != null && live == stored.applied) {
            VendorModeOriginResolution(origin = stored.origin, held = stored, retired = null)
        } else {
            VendorModeOriginResolution(origin = live, held = null, retired = stored)
        }

    /**
     * The record to persist for an apply of [target], given the [origin] from [resolveAtCapture].
     * Null means hold no record (and clear a held one): the apply targets the origin itself, so
     * Mimir leaves no deviation to guard.
     *
     * Persist the result BEFORE writing the key. An [origin] or [target] outside
     * [TuningPlanner.KNOWN_VENDOR_MODES] also answers null; that is unreachable in practice (the
     * axis is only present when the key reads inside the set, and the table only plans inside it)
     * and refusing to build a record is safer than throwing in the apply path.
     */
    fun recordAfterApply(origin: Int, target: Int, transactionId: String): VendorModeOriginRecord? {
        if (origin !in TuningPlanner.KNOWN_VENDOR_MODES || target !in TuningPlanner.KNOWN_VENDOR_MODES) {
            return null
        }
        if (target == origin || transactionId.isBlank()) return null
        return VendorModeOriginRecord(origin = origin, applied = target, transactionId = transactionId)
    }

    /**
     * Restore time: the three rows of the Guardian table. [origin] and [applied] come from the
     * restore entry (the daemon passes the stored record, the Guardian its envelope entry).
     */
    fun decideRestore(origin: Int, applied: Int, live: Int?): VendorModeRestoreAction = when {
        live == null -> VendorModeRestoreAction.UNREADABLE
        live == origin -> VendorModeRestoreAction.ALREADY_AT_ORIGIN
        live == applied -> VendorModeRestoreAction.WRITE_ORIGIN
        else -> VendorModeRestoreAction.SKIP_PLAYER_CHANGED
    }

    /**
     * The record to hold after a restore whose final readback was [readback]: kept ONLY when the
     * restore did not land -- the key is unreadable ([readback] null) or still reads Mimir's own
     * [VendorModeOriginRecord.applied] -- so the next attempt still knows the origin. Any other
     * readback clears it: the origin (restored), or a value the player chose (left alone). A record
     * kept across a player's skip would let a later session whose live read happens to equal
     * `applied` take Mimir's old origin for its own, and a restore then write it over the player's mode.
     */
    fun recordAfterRestore(held: VendorModeOriginRecord?, readback: Int?): VendorModeOriginRecord? =
        if (held != null && (readback == null || readback == held.applied)) held else null
}

/** Canonical JSON so a record written by one process reads identically in the other. */
internal object VendorModeOriginCodec {
    private const val KEY_SCHEMA = "schema"
    private const val KEY_ORIGIN = "origin"
    private const val KEY_APPLIED = "applied"
    private const val KEY_TRANSACTION = "transactionId"
    private const val SCHEMA = 1

    fun encode(record: VendorModeOriginRecord): ByteArray =
        JSONObject()
            .put(KEY_SCHEMA, SCHEMA)
            .put(KEY_ORIGIN, record.origin)
            .put(KEY_APPLIED, record.applied)
            .put(KEY_TRANSACTION, record.transactionId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    /** Null for anything that is not exactly a schema-1 record with in-range modes. */
    fun decode(bytes: ByteArray?): VendorModeOriginRecord? = runCatching {
        val json = JSONObject(String(bytes ?: return null, StandardCharsets.UTF_8))
        if (json.opt(KEY_SCHEMA) as? Int != SCHEMA) return null
        val origin = json.opt(KEY_ORIGIN) as? Int ?: return null
        val applied = json.opt(KEY_APPLIED) as? Int ?: return null
        val transaction = (json.opt(KEY_TRANSACTION) as? String)?.takeIf { it.isNotBlank() } ?: return null
        VendorModeOriginRecord(origin = origin, applied = applied, transactionId = transaction)
    }.getOrNull()
}

/** What the store found. A damaged file is reported, never silently read as "no record". */
internal sealed interface VendorModeOriginLoad {
    /** No record file: Mimir holds no deviation. */
    data object Absent : VendorModeOriginLoad

    data class Held(val record: VendorModeOriginRecord) : VendorModeOriginLoad

    /** A file is there but is unreadable or not a valid record. The policy treats it as no record. */
    data object Corrupt : VendorModeOriginLoad
}

/** The record to hand [VendorModeOriginPolicy], with [VendorModeOriginLoad.Corrupt] treated as none. */
internal val VendorModeOriginLoad.recordOrNull: VendorModeOriginRecord?
    get() = (this as? VendorModeOriginLoad.Held)?.record

/**
 * Device-protected, atomically replaced, and read by both the daemon and the guardian process.
 *
 * Device-protected rather than credential-protected because a restore may have to run before the
 * user has unlocked -- the same reason the guardian's own stores live there. The caller supplies the
 * directory; this class does not choose the storage class.
 *
 * Same write discipline as [StockCeilingBaselineStore]: a staging file, `fsync`, then an atomic
 * move over the record. Unlike it, a failed write is REPORTED: the baseline store can degrade to a
 * correct answer for the capture in flight, but a missing origin record has no such fallback.
 */
internal class VendorModeOriginStore(directory: File) {
    private val root = directory.absoluteFile
    private val record = File(root, RECORD_NAME)
    private val staging = File(root, ".$RECORD_NAME.tmp")

    init { require(root.isAbsolute) }

    @Synchronized
    fun load(): VendorModeOriginLoad {
        if (!record.exists()) return VendorModeOriginLoad.Absent
        val decoded = runCatching {
            record.takeIf(File::isFile)?.let { VendorModeOriginCodec.decode(it.readBytes()) }
        }.getOrNull()
        return decoded?.let(VendorModeOriginLoad::Held) ?: VendorModeOriginLoad.Corrupt
    }

    /** True only when the record is durable (staged, synced and atomically moved into place). */
    @Synchronized
    fun save(value: VendorModeOriginRecord): Boolean = runCatching {
        if (!root.exists() && !root.mkdirs()) return false
        if (staging.exists() && !staging.delete()) return false
        FileOutputStream(staging).use { output ->
            output.write(VendorModeOriginCodec.encode(value))
            output.flush()
            output.fd.sync()
        }
        Files.move(
            staging.toPath(),
            record.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrDefault(false)

    /** True when no record file remains afterwards (also true when there was none). */
    @Synchronized
    fun clear(): Boolean = runCatching {
        if (staging.exists()) staging.delete()
        if (record.exists() && !record.delete()) return false
        !record.exists()
    }.getOrDefault(false)

    companion object {
        const val DIRECTORY = "vendor_mode_origin"
        private const val RECORD_NAME = "origin.json"
    }
}
