package com.gruning.mimir.tweaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Bounded outer frame for one canonical READY receipt and its opaque proof-of-possession signature.
 * The canonical READY payload itself remains the sole schema/tag authority.
 */
internal object GuardianReadyWireCodec {
    private const val FRAME_OVERHEAD_BYTES = Int.SIZE_BYTES * 2
    /** Complete outer payload bound, including both big-endian length prefixes. */
    internal const val MAX_PAYLOAD_BYTES = GuardianArmRecordCodec.MAX_READY + GuardianArmRecordCodec.MAX_SIGNATURE + FRAME_OVERHEAD_BYTES
    private const val MAX_FRAME_BYTES = MAX_PAYLOAD_BYTES

    fun encode(canonicalReady: ByteArray, signature: ByteArray): ByteArray? = runCatching {
        require(canonicalReady.size in 1..GuardianArmRecordCodec.MAX_READY)
        require(signature.size in 1..GuardianArmRecordCodec.MAX_SIGNATURE)
        val receipt = GuardianArmRecordCodec.decodeReady(canonicalReady)
        require(receipt.canonicalBytes().contentEquals(canonicalReady))
        ByteArrayOutputStream(MAX_FRAME_BYTES).also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(canonicalReady.size)
                out.write(canonicalReady)
                out.writeInt(signature.size)
                out.write(signature)
            }
        }.toByteArray()
    }.getOrNull()

    fun decode(payload: ByteArray): SignedRestoreGuardianReadyReceipt? = runCatching {
        require(payload.size in (FRAME_OVERHEAD_BYTES + 2)..MAX_FRAME_BYTES)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val canonicalReady = input.readBoundedBytes(GuardianArmRecordCodec.MAX_READY)
            val signature = input.readBoundedBytes(GuardianArmRecordCodec.MAX_SIGNATURE)
            require(input.read() == -1)
            val receipt = GuardianArmRecordCodec.decodeReady(canonicalReady)
            require(receipt.canonicalBytes().contentEquals(canonicalReady))
            SignedRestoreGuardianReadyReceipt(receipt, signature)
        }
    }.getOrNull()

    private fun DataInputStream.readBoundedBytes(maxBytes: Int): ByteArray {
        val length = readInt()
        require(length in 1..maxBytes)
        return ByteArray(length).also(::readFully)
    }
}
