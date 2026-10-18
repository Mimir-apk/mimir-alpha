package com.gruning.mimir.tweaker

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Receipt-direction signing authority; it intentionally cannot stand in for an app signer. */
internal fun interface GuardianSessionSigner {
    fun sign(canonicalReceipt: ByteArray): ByteArray
}

/** Receipt-direction verification authority; it intentionally cannot verify app envelopes. */
internal fun interface GuardianSessionSignatureVerifier {
    fun verify(canonicalReceipt: ByteArray, signature: ByteArray): Boolean
}

internal class EcdsaP256GuardianSessionSigner(private val privateKey: PrivateKey) : GuardianSessionSigner {
    init { requireExactP256(privateKey) }

    override fun sign(canonicalReceipt: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(canonicalReceipt)
        sign()
    }
}

private class EcdsaP256GuardianSessionVerifier(private val publicKey: PublicKey) : GuardianSessionSignatureVerifier {
    init { requireExactP256(publicKey) }

    override fun verify(canonicalReceipt: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonicalReceipt)
            verify(signature)
        }
    }.getOrDefault(false)
}

/**
 * An arm-scoped SEC1 uncompressed P-256 public key. Construction is strict and all byte
 * projections are defensive, so a carried key cannot be changed after validation.
 */
internal class GuardianSessionPublicKey private constructor(
    sec1: ByteArray,
    private val publicKey: PublicKey,
) {
    private val pinnedSec1 = sec1.copyOf()
    private val pinnedDigest = GuardianCanonicalCodec.sha256(pinnedSec1)

    fun sec1Bytes(): ByteArray = pinnedSec1.copyOf()
    fun sha256(): String = pinnedDigest
    fun verifier(): GuardianSessionSignatureVerifier = EcdsaP256GuardianSessionVerifier(publicKey)

    override fun equals(other: Any?): Boolean = other is GuardianSessionPublicKey && pinnedSec1.contentEquals(other.pinnedSec1)
    override fun hashCode(): Int = pinnedSec1.contentHashCode()
    override fun toString(): String = "GuardianSessionPublicKey(sha256=$pinnedDigest)"

    companion object {
        const val SEC1_UNCOMPRESSED_BYTES = 65

        fun fromSec1(sec1: ByteArray): GuardianSessionPublicKey {
            val candidate = sec1.copyOf()
            require(candidate.size == SEC1_UNCOMPRESSED_BYTES && candidate[0] == 0x04.toByte()) { "invalid SEC1 P-256 point" }
            val x = BigInteger(1, candidate.copyOfRange(1, 33))
            val y = BigInteger(1, candidate.copyOfRange(33, 65))
            require(x > BigInteger.ZERO && x < SESSION_P256_FIELD_PRIME && y > BigInteger.ZERO && y < SESSION_P256_FIELD_PRIME) { "SEC1 coordinate out of bounds" }
            require(y.modPow(BigInteger.valueOf(2L), SESSION_P256_FIELD_PRIME) ==
                x.modPow(BigInteger.valueOf(3), SESSION_P256_FIELD_PRIME).add(SESSION_P256_A.multiply(x)).add(SESSION_P256_B).mod(SESSION_P256_FIELD_PRIME)) {
                "SEC1 point is off curve"
            }
            val params = AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            }
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
            requireExactP256(key)
            require(encodeSec1(key).contentEquals(candidate)) { "SEC1 Java round-trip mismatch" }
            return GuardianSessionPublicKey(candidate, key)
        }

        fun fromPublicKey(publicKey: PublicKey): GuardianSessionPublicKey {
            requireExactP256(publicKey)
            return fromSec1(encodeSec1(publicKey))
        }
    }
}

/** Pure injected channel metadata; RP5 values are intentionally not supplied here. */
internal data class GuardianReadyChannelEvidence(
    val ownerUid: Int,
    val ownerGid: Int,
    val modeOctal: String,
    val seLinuxLabel: String,
    val exactBytesConfirmed: Boolean,
) { init { requireChannelFields(ownerUid, ownerGid, modeOctal, seLinuxLabel) } }

internal class GuardianReadyChannelExpectation(
    val ownerUid: Int,
    val ownerGid: Int,
    val modeOctal: String,
    val seLinuxLabel: String,
    val exactBytesConfirmed: Boolean,
    forbiddenWriterUids: Set<Int>,
) {
    private val pinnedForbiddenWriterUids = java.util.Collections.unmodifiableSet(forbiddenWriterUids.toSet())
    val forbiddenWriterUids: Set<Int> get() = pinnedForbiddenWriterUids
    init {
        requireChannelFields(ownerUid, ownerGid, modeOctal, seLinuxLabel)
        require(exactBytesConfirmed) { "channel bytes must be exactly confirmed" }
        require(pinnedForbiddenWriterUids.all { it >= 0 }) { "invalid forbidden writer UID" }
    }
    fun copy(
        ownerUid: Int = this.ownerUid,
        ownerGid: Int = this.ownerGid,
        modeOctal: String = this.modeOctal,
        seLinuxLabel: String = this.seLinuxLabel,
        exactBytesConfirmed: Boolean = this.exactBytesConfirmed,
        forbiddenWriterUids: Set<Int> = this.forbiddenWriterUids,
    ) = GuardianReadyChannelExpectation(ownerUid, ownerGid, modeOctal, seLinuxLabel, exactBytesConfirmed, forbiddenWriterUids)
}

internal object GuardianReadyChannelPolicy {
    fun matches(evidence: GuardianReadyChannelEvidence, expected: GuardianReadyChannelExpectation): Boolean =
        evidence.ownerUid == expected.ownerUid &&
            evidence.ownerGid == expected.ownerGid &&
            evidence.modeOctal == expected.modeOctal &&
            evidence.seLinuxLabel == expected.seLinuxLabel &&
            evidence.exactBytesConfirmed == expected.exactBytesConfirmed &&
            evidence.exactBytesConfirmed &&
            evidence.ownerUid !in expected.forbiddenWriterUids
}

private fun requireChannelFields(ownerUid: Int, ownerGid: Int, modeOctal: String, seLinuxLabel: String) {
    require(ownerUid >= 0 && ownerGid >= 0) { "invalid channel owner" }
    require(Regex("[0-7]{3,4}").matches(modeOctal)) { "invalid channel mode" }
    require(
        seLinuxLabel.isNotBlank() && seLinuxLabel.length <= 256 &&
            !seLinuxLabel.any { it.isWhitespace() || it.isISOControl() },
    ) { "invalid SELinux label" }
}

private fun encodeSec1(publicKey: PublicKey): ByteArray {
    val ec = publicKey as? ECPublicKey ?: throw IllegalArgumentException("session key must be EC public key")
    val x = ec.w.affineX.toFixed32()
    val y = ec.w.affineY.toFixed32()
    return byteArrayOf(0x04) + x + y
}

private fun BigInteger.toFixed32(): ByteArray {
    require(signum() >= 0 && bitLength() <= 256) { "P-256 coordinate invalid" }
    val raw = toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
    require(raw.size <= 32)
    return ByteArray(32 - raw.size) + raw
}

private fun requireExactP256(key: Any) {
    val ec = key as? ECKey ?: throw IllegalArgumentException("session key must be EC P-256")
    val params = ec.params ?: throw IllegalArgumentException("session EC parameters missing")
    val field = params.curve.field as? ECFieldFp
    require(field?.p == SESSION_P256_FIELD_PRIME && params.curve.a == SESSION_P256_A && params.curve.b == SESSION_P256_B &&
        params.generator.affineX == SESSION_P256_GENERATOR_X && params.generator.affineY == SESSION_P256_GENERATOR_Y &&
        params.order == SESSION_P256_ORDER && params.cofactor == 1) { "session key must be exact P-256" }
}

private fun sessionHex(value: String) = BigInteger(value, 16)
private val SESSION_P256_FIELD_PRIME = sessionHex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
private val SESSION_P256_A = sessionHex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC")
private val SESSION_P256_B = sessionHex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
private val SESSION_P256_GENERATOR_X = sessionHex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
private val SESSION_P256_GENERATOR_Y = sessionHex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")
private val SESSION_P256_ORDER = sessionHex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
