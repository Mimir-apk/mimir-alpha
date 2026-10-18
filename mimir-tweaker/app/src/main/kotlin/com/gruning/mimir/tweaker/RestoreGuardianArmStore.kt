package com.gruning.mimir.tweaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable, authenticated PS04D4B arm truth.  It deliberately has no controller authority. */
internal interface RestoreGuardianArmStore {
    fun arm(signed: SignedRestoreGuardianEnvelope): GuardianArmStoreWrite
    fun load(expected: RestoreGuardianExpectedIdentity): GuardianArmStoreLoad
    fun pinSessionKey(expected: RestoreGuardianExpectedIdentity, sessionKey: GuardianSessionPublicKey, signedReady: SignedRestoreGuardianReadyReceipt, nowElapsedRealtimeMs: Long): GuardianSessionPinWrite
    /** Presence only: never decodes, verifies, clears, or turns an orphan into authority. */
    fun presence(): GuardianArmStorePresence
    fun clear(): Boolean

}

internal enum class GuardianArmStorePresence { ABSENT, PRESENT, UNREADABLE }

internal data class RestoreGuardianExpectedIdentity(
    val helperBuildIdentity: String, val tweakerCertificateSha256: String, val hardwareFingerprint: String,
    val deviceProfile: String, val bootIdentity: String, val generation: Long, val requestId: String,
    val transactionId: String, val taskNonce: String, val topologySha256: String, val snapshotSha256: String,
    val hardDeadlineElapsedRealtimeMs: Long,
) {
    /*
     * Deliberately carries NO plan hash. It used to, sourced from `record.planHash`, and that made
     * the pairing depend on applied state that a lawful WAVE2 derate necessarily moves: after one
     * actuated rung the derived identity no longer matched the stored envelope, `readStaged`'s
     * `require(expected.matches(envelope))` threw, the arm loaded Corrupt, and `restoreAndSettle`
     * returned GUARDIAN_READY_UNAVAILABLE before any restore was requested -- stranding the record
     * at RESTORING so every later apply was refused SESSION_NOT_SETTLED until app data was cleared.
     * Device-reproduced on the shipping build 2026-08-09.
     *
     * Nothing is lost. The envelope's canonical bytes include its planSha256, so
     * `binding.envelopeSha256 == envelope.digestSha256()` already pins the arm-time plan, and the
     * envelope signature and journal MAC pin the rest. What the field equality additionally
     * asserted was "the record's plan has not changed since arming" -- exactly the invariant the
     * derate ladder lawfully retires. Arming is unaffected: the envelope is minted from the arm
     * REQUEST identity, not from this type.
     */
    init { GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build"); GuardianEnvelopeValidation.requireSha256(tweakerCertificateSha256, "certificate"); GuardianEnvelopeValidation.requireToken(hardwareFingerprint, "hardware fingerprint"); GuardianEnvelopeValidation.requireToken(deviceProfile, "device profile"); GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity"); require(generation > 0); GuardianEnvelopeValidation.requireToken(requestId, "request"); GuardianEnvelopeValidation.requireToken(transactionId, "transaction"); GuardianEnvelopeValidation.requireNonce(taskNonce); GuardianEnvelopeValidation.requireSha256(topologySha256, "topology"); GuardianEnvelopeValidation.requireSha256(snapshotSha256, "snapshot"); require(hardDeadlineElapsedRealtimeMs > 0) }
    fun matches(value: RestoreGuardianEnvelope): Boolean = helperBuildIdentity == value.helperBuildIdentity && tweakerCertificateSha256 == value.tweakerCertificateSha256 && hardwareFingerprint == value.hardwareFingerprint && deviceProfile == value.deviceProfile && bootIdentity == value.bootIdentity && generation == value.generation && requestId == value.requestId && transactionId == value.transactionId && taskNonce == value.taskNonce && topologySha256 == value.topologySha256 && snapshotSha256 == value.snapshotSha256 && hardDeadlineElapsedRealtimeMs == value.hardDeadlineElapsedRealtimeMs
}

/** Reconstructs only the exact identity already authenticated by a schema-v5 journal. */
internal object GuardianArmExpectedIdentityDerivation {
    fun from(record: TransactionJournalRecord): RestoreGuardianExpectedIdentity? {
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) return null
        val binding = record.guardianBinding ?: return null
        return runCatching {
            RestoreGuardianExpectedIdentity(
                helperBuildIdentity = binding.helperBuildIdentity,
                tweakerCertificateSha256 = binding.tweakerCertificateSha256,
                hardwareFingerprint = record.hardwareFingerprint,
                deviceProfile = record.deviceProfile,
                bootIdentity = record.bootIdentity,
                generation = record.generation,
                requestId = record.requestId,
                transactionId = record.transactionId,
                taskNonce = binding.taskNonce,
                topologySha256 = record.expectedTopologySha256,
                snapshotSha256 = record.snapshotSha256,
                hardDeadlineElapsedRealtimeMs = binding.hardDeadlineElapsedRealtimeMs,
            )
        }.getOrNull()
    }
}

internal sealed interface GuardianArmStoreWrite { data object Armed : GuardianArmStoreWrite; data object Rejected : GuardianArmStoreWrite }
internal sealed interface GuardianArmStoreLoad {
    data object Empty : GuardianArmStoreLoad
    data class LoadedStaged(val signed: SignedRestoreGuardianEnvelope) : GuardianArmStoreLoad
    data class LoadedReady(val signed: SignedRestoreGuardianEnvelope, val sessionKey: GuardianSessionPublicKey, val signedReady: SignedRestoreGuardianReadyReceipt, val signedPin: SignedRestoreGuardianSessionPin) : GuardianArmStoreLoad
    data object Corrupt : GuardianArmStoreLoad
}
internal sealed interface GuardianSessionPinWrite { data object Pinned : GuardianSessionPinWrite; data object Rejected : GuardianSessionPinWrite; data object Ambiguous : GuardianSessionPinWrite }

/** Host implementation intentionally goes through the same codec as durable storage. */
internal class InMemoryRestoreGuardianArmStore(
    private val available: DirectBootKeyAuthorityTruth.Available,
    private val readyRecordEncoder: (SignedRestoreGuardianEnvelope, PinnedRestoreGuardianVerifier, GuardianSessionPublicKey, SignedRestoreGuardianReadyReceipt, SignedRestoreGuardianSessionPin) -> ByteArray = GuardianArmRecordCodec::encodeReady,
) : RestoreGuardianArmStore {
    private val pinned = available.envelopeVerifier()
    private var record: ByteArray? = null
    @Synchronized override fun arm(signed: SignedRestoreGuardianEnvelope): GuardianArmStoreWrite {
        if (record != null) return GuardianArmStoreWrite.Rejected
        val bytes = runCatching { GuardianArmRecordCodec.encodeStaged(signed, pinned) }.getOrNull() ?: return GuardianArmStoreWrite.Rejected
        record = bytes.copyOf(); return GuardianArmStoreWrite.Armed
    }
    @Synchronized override fun load(expected: RestoreGuardianExpectedIdentity): GuardianArmStoreLoad = record?.let { GuardianArmRecordCodec.decodeAndVerify(it.copyOf(), expected, pinned) } ?: GuardianArmStoreLoad.Empty
    @Synchronized override fun presence(): GuardianArmStorePresence =
        if (record == null) GuardianArmStorePresence.ABSENT else GuardianArmStorePresence.PRESENT
    @Synchronized override fun pinSessionKey(expected: RestoreGuardianExpectedIdentity, sessionKey: GuardianSessionPublicKey, signedReady: SignedRestoreGuardianReadyReceipt, nowElapsedRealtimeMs: Long): GuardianSessionPinWrite {
        val staged = load(expected) as? GuardianArmStoreLoad.LoadedStaged ?: return GuardianSessionPinWrite.Rejected
        val ready = runCatching {
            val pin = validPin(staged.signed, sessionKey, signedReady, available, nowElapsedRealtimeMs) ?: return@runCatching null
            readyRecordEncoder(staged.signed, pinned, sessionKey, signedReady, pin)
        }.getOrNull() ?: return GuardianSessionPinWrite.Rejected
        record = ready.copyOf()
        return GuardianSessionPinWrite.Pinned
    }
    @Synchronized override fun clear(): Boolean { val existed = record != null; record = null; return existed }
}

internal class FileRestoreGuardianArmStore(
    private val available: DirectBootKeyAuthorityTruth.Available,
    private val directory: File,
    recordName: String = "restore-guardian.arm",
    private val atomicMove: (File, File) -> Unit = { source, destination -> Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit },
    private val readyRecordEncoder: (SignedRestoreGuardianEnvelope, PinnedRestoreGuardianVerifier, GuardianSessionPublicKey, SignedRestoreGuardianReadyReceipt, SignedRestoreGuardianSessionPin) -> ByteArray = GuardianArmRecordCodec::encodeReady,
) : RestoreGuardianArmStore {
    private val pinned = available.envelopeVerifier()
    private val recordFile: File
    private val stagingFile: File
    init { require(directory.isAbsolute) { "arm directory must be absolute" }; require(recordName.matches(Regex("[A-Za-z0-9._-]{1,96}"))); recordFile = File(directory, recordName); stagingFile = File(directory, ".$recordName.tmp") }

    @Synchronized override fun arm(signed: SignedRestoreGuardianEnvelope): GuardianArmStoreWrite {
        if (existingTruth() !is GuardianArmStoreLoad.Empty) return GuardianArmStoreWrite.Rejected
        val bytes = runCatching { GuardianArmRecordCodec.encodeStaged(signed, pinned) }.getOrNull() ?: return GuardianArmStoreWrite.Rejected
        return runCatching { ensurePrivateDirectory(); writePrivateAndSync(stagingFile, bytes); atomicMove(stagingFile, recordFile); syncParentBestEffort(); GuardianArmStoreWrite.Armed }.getOrElse {
            runCatching { if (stagingFile.exists()) stagingFile.delete() }; GuardianArmStoreWrite.Rejected
        }
    }

    @Synchronized override fun load(expected: RestoreGuardianExpectedIdentity): GuardianArmStoreLoad {
        if (stagingFile.exists()) return GuardianArmStoreLoad.Corrupt
        if (!recordFile.exists()) return GuardianArmStoreLoad.Empty
        return runCatching { GuardianArmRecordCodec.decodeAndVerify(strictRead(recordFile), expected, pinned) }.getOrDefault(GuardianArmStoreLoad.Corrupt)
    }

    @Synchronized override fun presence(): GuardianArmStorePresence = when {
        stagingFile.exists() -> GuardianArmStorePresence.UNREADABLE
        recordFile.exists() -> GuardianArmStorePresence.PRESENT
        else -> GuardianArmStorePresence.ABSENT
    }

    @Synchronized override fun pinSessionKey(expected: RestoreGuardianExpectedIdentity, sessionKey: GuardianSessionPublicKey, signedReady: SignedRestoreGuardianReadyReceipt, nowElapsedRealtimeMs: Long): GuardianSessionPinWrite {
        val before = runCatching { strictRead(recordFile) }.getOrNull() ?: return GuardianSessionPinWrite.Rejected
        val staged = GuardianArmRecordCodec.decodeAndVerify(before, expected, pinned) as? GuardianArmStoreLoad.LoadedStaged ?: return GuardianSessionPinWrite.Rejected
        if (stagingFile.exists()) return GuardianSessionPinWrite.Rejected
        val ready = runCatching {
            val pin = validPin(staged.signed, sessionKey, signedReady, available, nowElapsedRealtimeMs) ?: return@runCatching null
            readyRecordEncoder(staged.signed, pinned, sessionKey, signedReady, pin)
        }.getOrNull() ?: return GuardianSessionPinWrite.Rejected
        return try {
            ensurePrivateDirectory(); writePrivateAndSync(stagingFile, ready); atomicMove(stagingFile, recordFile); syncParentBestEffort(); GuardianSessionPinWrite.Pinned
        } catch (unsupported: AtomicMoveNotSupportedException) {
            if (cleanupAndExactlyStaged(before)) GuardianSessionPinWrite.Rejected else GuardianSessionPinWrite.Ambiguous
        } catch (_: Throwable) {
            val reload = load(expected)
            when {
                reload is GuardianArmStoreLoad.LoadedReady && sameReady(reload, staged.signed, sessionKey, signedReady) -> GuardianSessionPinWrite.Pinned
                cleanupAndExactlyStaged(before) -> GuardianSessionPinWrite.Rejected
                else -> GuardianSessionPinWrite.Ambiguous
            }
        }
    }

    @Synchronized override fun clear(): Boolean = runCatching { val hadRecord = recordFile.exists(); val hadStaging = stagingFile.exists(); if (hadStaging && !stagingFile.delete()) return@runCatching false; if (hadRecord && !recordFile.delete()) return@runCatching false; syncParentBestEffort(); (hadRecord || hadStaging) && !recordFile.exists() && !stagingFile.exists() }.getOrDefault(false)

    private fun cleanupAndExactlyStaged(before: ByteArray): Boolean {
        if (stagingFile.exists() && !stagingFile.delete()) return false
        return !stagingFile.exists() && recordFile.exists() && runCatching { strictRead(recordFile).contentEquals(before) }.getOrDefault(false)
    }
    private fun existingTruth(): GuardianArmStoreLoad = if (stagingFile.exists() || recordFile.exists()) GuardianArmStoreLoad.Corrupt else GuardianArmStoreLoad.Empty
    private fun ensurePrivateDirectory() { if (!directory.exists() && !directory.mkdirs()) error("arm directory unavailable"); require(directory.isDirectory); directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false); directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true) }
    private fun writePrivateAndSync(file: File, bytes: ByteArray) { FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }; file.setReadable(false, false); file.setWritable(false, false); file.setReadable(true, true); file.setWritable(true, true) }
    private fun strictRead(file: File): ByteArray { require(file.length() in 1..GuardianArmRecordCodec.MAX_RECORD_BYTES.toLong()); return FileInputStream(file).use { it.readBytes().also { bytes -> require(bytes.size == file.length().toInt()) } } }
    private fun syncParentBestEffort() { runCatching { FileInputStream(directory).use { it.fd.sync() } } }
}

private fun validPin(staged: SignedRestoreGuardianEnvelope, sessionKey: GuardianSessionPublicKey, signedReady: SignedRestoreGuardianReadyReceipt, available: DirectBootKeyAuthorityTruth.Available, now: Long): SignedRestoreGuardianSessionPin? {
    if (now < 0 || now > staged.envelope.hardDeadlineElapsedRealtimeMs || signedReady.receipt.sessionPublicKey != sessionKey || !RestoreGuardianCrypto.verifyReadyReceipt(signedReady, staged.envelope) || !RestoreGuardianCrypto.receiptMatches(staged.envelope, signedReady.receipt) || now < signedReady.receipt.issuedElapsedRealtimeMs) return null
    val pin = RestoreGuardianSessionPin(envelopeSha256 = staged.envelope.digestSha256(), sessionKeySha256 = sessionKey.sha256(), readyReceiptSha256 = GuardianCanonicalCodec.sha256(signedReady.receipt.canonicalBytes()), readyIssuedElapsedRealtimeMs = signedReady.receipt.issuedElapsedRealtimeMs, pinnedElapsedRealtimeMs = now)
    return available.signSessionPin(pin)
}
private fun sameReady(actual: GuardianArmStoreLoad.LoadedReady, staged: SignedRestoreGuardianEnvelope, key: GuardianSessionPublicKey, ready: SignedRestoreGuardianReadyReceipt): Boolean = actual.signed.envelope.canonicalBytes().contentEquals(staged.envelope.canonicalBytes()) && actual.signed.signature.contentEquals(staged.signature) && actual.sessionKey == key && actual.signedReady.receipt.canonicalBytes().contentEquals(ready.receipt.canonicalBytes()) && actual.signedReady.signature.contentEquals(ready.signature)

/** MGRA v3 only. A record has either its STAGED body or that exact body plus READY evidence. */
internal object GuardianArmRecordCodec {
    const val MAX_RECORD_BYTES = 128 * 1024
    private const val MAGIC = 0x4D475241; private const val VERSION = 3; private const val STAGED = 1; private const val READY = 2
    private const val MAX_ENVELOPE = 96 * 1024; internal const val MAX_SIGNATURE = 512; private const val MAX_KEY = 4 * 1024; internal const val MAX_READY = 2 * 1024; private const val MAX_PIN = 1024
    fun encode(signed: SignedRestoreGuardianEnvelope, pinned: PinnedRestoreGuardianVerifier): ByteArray = encodeStaged(signed, pinned)
    fun encodeStaged(signed: SignedRestoreGuardianEnvelope, pinned: PinnedRestoreGuardianVerifier): ByteArray = record(STAGED) { out -> staged(out, signed, pinned) }
    fun encodeReady(signed: SignedRestoreGuardianEnvelope, pinned: PinnedRestoreGuardianVerifier, sessionKey: GuardianSessionPublicKey, signedReady: SignedRestoreGuardianReadyReceipt, signedPin: SignedRestoreGuardianSessionPin): ByteArray {
        require(RestoreGuardianCrypto.verifyReadyReceipt(signedReady, signed.envelope)); require(signedReady.receipt.sessionPublicKey == sessionKey); require(RestoreGuardianCrypto.verifySessionPin(signedPin, pinned, signed.envelope)); require(pinMatches(signedPin.pin, signed.envelope, sessionKey, signedReady))
        return record(READY) { out -> staged(out, signed, pinned); out.bytes(sessionKey.sec1Bytes()); out.bytes(signedReady.receipt.canonicalBytes()); out.bytes(signedReady.signature); out.bytes(signedPin.pin.canonicalBytes()); out.bytes(signedPin.signature) }
    }
    fun decodeAndVerify(bytes: ByteArray, expected: RestoreGuardianExpectedIdentity, pinned: PinnedRestoreGuardianVerifier): GuardianArmStoreLoad = runCatching {
        require(bytes.size in 1..MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION); val state = input.readInt(); val body = readStaged(input, expected, pinned)
            val result = when (state) { STAGED -> GuardianArmStoreLoad.LoadedStaged(body.signed); READY -> readReady(input, body, pinned); else -> error("unknown state") }
            require(input.read() == -1); result
        }
    }.getOrDefault(GuardianArmStoreLoad.Corrupt)
    private data class Staged(val signed: SignedRestoreGuardianEnvelope)
    private fun staged(out: DataOutputStream, signed: SignedRestoreGuardianEnvelope, pinned: PinnedRestoreGuardianVerifier) { require(RestoreGuardianCrypto.verifyEnvelope(signed, pinned)); val envelope = signed.envelope.canonicalBytes(); require(envelope.size in 1..MAX_ENVELOPE && signed.signature.size in 1..MAX_SIGNATURE); out.bytes(envelope); out.bytes(signed.signature); pinned.writeArmRecordBinding(out) }
    private fun readStaged(input: DataInputStream, expected: RestoreGuardianExpectedIdentity, pinned: PinnedRestoreGuardianVerifier): Staged { val canonical = input.bytes(MAX_ENVELOPE); val signature = input.bytes(MAX_SIGNATURE); val storedKey = input.bytes(MAX_KEY); val certificate = input.string(64); require(pinned.matchesArmRecordBinding(storedKey, certificate)); val envelope = decodeEnvelope(canonical); require(envelope.canonicalBytes().contentEquals(canonical) && expected.matches(envelope)); val signed = SignedRestoreGuardianEnvelope(envelope, signature); require(RestoreGuardianCrypto.verifyEnvelope(signed, pinned)); return Staged(signed) }
    private fun readReady(input: DataInputStream, staged: Staged, pinned: PinnedRestoreGuardianVerifier): GuardianArmStoreLoad.LoadedReady { val key = GuardianSessionPublicKey.fromSec1(input.bytes(GuardianSessionPublicKey.SEC1_UNCOMPRESSED_BYTES)); val readyCanonical = input.bytes(MAX_READY); val readySignature = input.bytes(MAX_SIGNATURE); val pinCanonical = input.bytes(MAX_PIN); val pinSignature = input.bytes(MAX_SIGNATURE); val receipt = decodeReady(readyCanonical); require(receipt.canonicalBytes().contentEquals(readyCanonical) && receipt.sessionPublicKey == key); val signedReady = SignedRestoreGuardianReadyReceipt(receipt, readySignature); require(RestoreGuardianCrypto.verifyReadyReceipt(signedReady, staged.signed.envelope)); val pin = decodePin(pinCanonical); require(pin.canonicalBytes().contentEquals(pinCanonical)); val signedPin = SignedRestoreGuardianSessionPin(pin, pinSignature); require(RestoreGuardianCrypto.verifySessionPin(signedPin, pinned, staged.signed.envelope) && pinMatches(pin, staged.signed.envelope, key, signedReady)); return GuardianArmStoreLoad.LoadedReady(staged.signed, key, signedReady, signedPin) }
    private fun pinMatches(pin: RestoreGuardianSessionPin, envelope: RestoreGuardianEnvelope, key: GuardianSessionPublicKey, ready: SignedRestoreGuardianReadyReceipt): Boolean = pin.envelopeSha256 == envelope.digestSha256() && pin.sessionKeySha256 == key.sha256() && pin.readyReceiptSha256 == GuardianCanonicalCodec.sha256(ready.receipt.canonicalBytes()) && pin.readyIssuedElapsedRealtimeMs == ready.receipt.issuedElapsedRealtimeMs && pin.pinnedElapsedRealtimeMs >= pin.readyIssuedElapsedRealtimeMs && pin.pinnedElapsedRealtimeMs <= envelope.hardDeadlineElapsedRealtimeMs
    private fun record(state: Int, body: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { sink -> DataOutputStream(sink).use { out -> out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(state); body(out) } }.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
    internal fun decodeReady(bytes: ByteArray): RestoreGuardianReadyReceipt = DataInputStream(ByteArrayInputStream(bytes)).use { i -> require(i.string(64) == "MIMIR_RESTORE_GUARDIAN_READY"); val value = RestoreGuardianReadyReceipt(i.readInt(), i.string(256), i.string(256), i.readLong(), i.string(256), i.string(256), i.string(128), i.string(64), GuardianSessionPublicKey.fromSec1(i.bytes(GuardianSessionPublicKey.SEC1_UNCOMPRESSED_BYTES)), i.readLong()); require(i.read() == -1); value }
    private fun decodePin(bytes: ByteArray): RestoreGuardianSessionPin = DataInputStream(ByteArrayInputStream(bytes)).use { i -> require(i.string(64) == "MIMIR_RESTORE_GUARDIAN_SESSION_PIN"); val value = RestoreGuardianSessionPin(i.readInt(), i.string(64), i.string(64), i.string(64), i.readLong(), i.readLong()); require(i.read() == -1); value }
    private fun decodeEnvelope(bytes: ByteArray): RestoreGuardianEnvelope = DataInputStream(ByteArrayInputStream(bytes)).use { i -> require(i.string(64) == "MIMIR_RESTORE_GUARDIAN_ENVELOPE"); val schema = i.readInt(); val helper = i.string(256); val certificate = i.string(64); val hardware = i.string(256); val profile = i.string(256); val boot = i.string(256); val generation = i.readLong(); val request = i.string(256); val transaction = i.string(256); val nonce = i.string(128); val topology = i.string(64); val snapshot = i.string(64); val plan = i.string(64); val deadline = i.readLong(); val count = i.readInt(); require(count in 1..GuardianEnvelopeValidation.MAX_RESTORE_ENTRIES); val entries = List(count) { i.restoreEntry() }; require(i.read() == -1); RestoreGuardianEnvelope(schema, helper, certificate, hardware, profile, boot, generation, request, transaction, nonce, topology, snapshot, plan, deadline, entries) }
    private fun DataInputStream.restoreEntry(): RestoreGuardianRestoreEntry { val kind = string(32); val ordinal = readInt(); val logicalId = string(256); val count = readInt(); require(count in 0..32); val intermediate = List(count) { readInt() }; return when (kind) { "sysfs" -> RestoreGuardianSysfsEntry(ordinal, logicalId, string(512), RestoreGuardianStockState.PresentSysfs(run { require(string(32) == "present_sysfs"); readInt() }, string(8), readInt(), readInt()), intermediate); "fan_put" -> { require(string(32) == "present_fan"); RestoreGuardianFanPutEntry(ordinal, logicalId, RestoreGuardianStockState.PresentFanMode(readInt()), intermediate) }; "fan_delete" -> { require(string(32) == "absent_fan"); RestoreGuardianFanDeleteEntry(ordinal, logicalId, RestoreGuardianStockState.AbsentFanMode, intermediate) }; "vendor_put" -> { require(string(32) == "present_vendor"); val origin = readInt(); require(intermediate.size == 1); RestoreGuardianVendorModePutEntry(ordinal, logicalId, origin, intermediate.single()) }; else -> error("entry") } }
    private fun DataOutputStream.bytes(value: ByteArray) { writeInt(value.size); write(value) }; private fun DataOutputStream.string(value: String) = bytes(value.toByteArray(Charsets.UTF_8)); private fun DataInputStream.bytes(max: Int): ByteArray { val size = readInt(); require(size in 1..max); return ByteArray(size).also(::readFully) }; private fun DataInputStream.string(max: Int): String = bytes(max).toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).size <= max) }
}
