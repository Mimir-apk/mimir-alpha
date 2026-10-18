package com.gruning.mimir.tweaker.mgde

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import com.gruning.mimir.tweaker.GuardianEnvelopeValidation
import com.gruning.mimir.tweaker.RestoreGuardianEnvelope
import com.gruning.mimir.tweaker.RestoreGuardianFanDeleteEntry
import com.gruning.mimir.tweaker.RestoreGuardianFanPutEntry
import com.gruning.mimir.tweaker.RestoreGuardianRestoreEntry
import com.gruning.mimir.tweaker.RestoreGuardianStockState
import com.gruning.mimir.tweaker.RestoreGuardianSysfsEntry
import com.gruning.mimir.tweaker.RestoreGuardianVendorModePutEntry

/** Pure, unwired MGDE v2 framing around the existing canonical envelope. */
internal class MgdeV2Record private constructor(
    envelope: ByteArray,
    signature: ByteArray,
    decodedEnvelope: RestoreGuardianEnvelope,
) {
    private val pinnedEnvelope = envelope.copyOf()
    private val pinnedSignature = signature.copyOf()
    private val pinnedDecodedEnvelope = decodedEnvelope.mgdeSnapshot()

    val envelope: ByteArray get() = pinnedEnvelope.copyOf()
    val signature: ByteArray get() = pinnedSignature.copyOf()
    val decodedEnvelope: RestoreGuardianEnvelope
        get() = pinnedDecodedEnvelope.mgdeSnapshot()

    companion object {
        /** The only non-private record creation path takes an entire framed wire value. */
        internal fun strictDecodeWire(bytes: ByteArray): MgdeV2Record? = runCatching {
            require(bytes.size in 1..MgdeV2Codec.MAX_RECORD)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(input.remaining() >= 16)
            require(input.int == MgdeV2Codec.MAGIC)
            require(input.int == MgdeV2Codec.VERSION)
            val envelope = MgdeV2Codec.take(input, input.int, MgdeV2Codec.MAX_ENVELOPE)
            val signature = MgdeV2Codec.take(input, input.int, MgdeV2Codec.MAX_SIGNATURE)
            val decoded = MgdeV2Codec.decodeEnvelopeStrict(envelope)
            require(!input.hasRemaining() && MgdeV2Codec.isCanonicalP256Der(signature))
            MgdeV2Record(envelope, signature, decoded)
        }.getOrNull()
    }
}

internal object MgdeV2Codec {
    internal const val MAGIC = 0x4d474445
    internal const val VERSION = 2
    const val MAX_ENVELOPE = 98_304
    const val MAX_SIGNATURE = 512
    const val MAX_RECORD = 131_072

    fun encode(envelope: RestoreGuardianEnvelope, signature: ByteArray): ByteArray {
        val canonical = envelope.canonicalBytes()
        require(canonical.size in 1..MAX_ENVELOPE)
        require(signature.size in 1..MAX_SIGNATURE && isCanonicalP256Der(signature))
        require(decodeEnvelopeStrict(canonical).canonicalBytes().contentEquals(canonical))
        return ByteArrayOutputStream().also { out ->
            out.write(int(MAGIC)); out.write(int(VERSION)); out.write(int(canonical.size)); out.write(canonical)
            out.write(int(signature.size)); out.write(signature)
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): MgdeV2Record? = MgdeV2Record.strictDecodeWire(bytes)

    internal fun take(input: ByteBuffer, length: Int, max: Int): ByteArray {
        require(length in 1..max && length <= input.remaining())
        return ByteArray(length).also(input::get)
    }

    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    internal fun decodeEnvelopeStrict(bytes: ByteArray): RestoreGuardianEnvelope = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        fun text(max: Int): String {
            val size = input.readInt()
            require(size in 1..max)
            val raw = ByteArray(size).also(input::readFully)
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
                .also { require(it.toByteArray(Charsets.UTF_8).contentEquals(raw)) }
        }
        require(text(64) == "MIMIR_RESTORE_GUARDIAN_ENVELOPE")
        val schema = input.readInt()
        val helper = text(256)
        val cert = text(64)
        val hardware = text(256)
        val profile = text(256)
        val boot = text(256)
        val generation = input.readLong()
        val request = text(256)
        val transaction = text(256)
        val nonce = text(128)
        val topology = text(64)
        val snapshot = text(64)
        val plan = text(64)
        val deadline = input.readLong()
        val count = input.readInt()
        require(count in 1..GuardianEnvelopeValidation.MAX_RESTORE_ENTRIES)
        val entries = (0 until count).map {
            val tag = text(32)
            val ordinal = input.readInt()
            val logical = text(256)
            val intermediateCount = input.readInt()
            require(intermediateCount in 0..32)
            val intermediate = (0 until intermediateCount).map { input.readInt() }
            fun stock(): RestoreGuardianStockState = when (text(32)) {
                "present_sysfs" -> RestoreGuardianStockState.PresentSysfs(
                    input.readInt(),
                    text(8),
                    input.readInt(),
                    input.readInt(),
                )
                "present_fan" -> RestoreGuardianStockState.PresentFanMode(input.readInt())
                "absent_fan" -> RestoreGuardianStockState.AbsentFanMode
                "present_vendor" -> RestoreGuardianStockState.PresentVendorMode(input.readInt())
                else -> error("stock tag")
            }
            when (tag) {
                "sysfs" -> RestoreGuardianSysfsEntry(
                    ordinal,
                    logical,
                    text(512),
                    stock() as RestoreGuardianStockState.PresentSysfs,
                    intermediate,
                )
                "fan_put" -> RestoreGuardianFanPutEntry(
                    ordinal,
                    logical,
                    stock() as RestoreGuardianStockState.PresentFanMode,
                    intermediate,
                )
                "fan_delete" -> RestoreGuardianFanDeleteEntry(
                    ordinal,
                    logical,
                    stock() as RestoreGuardianStockState.AbsentFanMode,
                    intermediate,
                )
                "vendor_put" -> {
                    val origin = (stock() as RestoreGuardianStockState.PresentVendorMode).value
                    require(intermediate.size == 1)
                    RestoreGuardianVendorModePutEntry(ordinal, logical, origin, intermediate.single())
                }
                else -> error("entry tag")
            }
        }
        require(input.read() == -1)
        RestoreGuardianEnvelope(
            schema,
            helper,
            cert,
            hardware,
            profile,
            boot,
            generation,
            request,
            transaction,
            nonce,
            topology,
            snapshot,
            plan,
            deadline,
            entries,
        ).also { require(it.canonicalBytes().contentEquals(bytes)) }
    }

    /**
     * Canonical P-256 ECDSA DER is always a short-form SEQUENCE containing two short-form,
     * minimally encoded, positive nonzero INTEGERs of at most 256 bits each.
     */
    internal fun isCanonicalP256Der(value: ByteArray): Boolean {
        if (value.size !in 8..72 || value[0].toInt() != 0x30) return false
        if ((value[1].toInt() and 0xff) != value.size - 2) return false
        var p = 2
        repeat(2) {
            if (p >= value.size || value[p++].toInt() != 0x02) return false
            if (p >= value.size) return false
            val size = value[p++].toInt() and 0xff
            if (size !in 1..33 || p + size > value.size) return false
            val first = value[p].toInt() and 0xff
            if (size == 1 && first == 0) return false
            if (first == 0) {
                if (size < 2 || size - 1 > 32 || value[p + 1].toInt() and 0x80 == 0) return false
            } else {
                if (size > 32 || first and 0x80 != 0) return false
            }
            p += size
        }
        return p == value.size
    }
}

private fun RestoreGuardianEnvelope.mgdeSnapshot(): RestoreGuardianEnvelope =
    copy(restoreEntries = restoreEntries.map { it.mgdeSnapshot() })

private fun RestoreGuardianRestoreEntry.mgdeSnapshot(): RestoreGuardianRestoreEntry = when (this) {
    is RestoreGuardianSysfsEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianFanPutEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianFanDeleteEntry -> copy(rendererAppliedIntermediates = rendererAppliedIntermediates.toList())
    is RestoreGuardianVendorModePutEntry -> copy()
}
