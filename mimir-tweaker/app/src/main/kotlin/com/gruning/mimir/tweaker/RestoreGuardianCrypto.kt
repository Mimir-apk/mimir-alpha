package com.gruning.mimir.tweaker

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECKey
import java.security.spec.ECFieldFp
import java.math.BigInteger

/** Keystore integration is intentionally deferred; this pure port keeps the private key out of staged files. */
internal fun interface RestoreGuardianSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

internal fun interface RestoreGuardianSignatureVerifier {
    fun verify(canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

internal class EcdsaP256Signer(private val privateKey: PrivateKey) : RestoreGuardianSigner {
    init { requireP256(privateKey) }

    override fun sign(canonicalPayload: ByteArray): ByteArray = Signature.getInstance(ALGORITHM).run {
        initSign(privateKey); update(canonicalPayload); sign()
    }

    private companion object { const val ALGORITHM = "SHA256withECDSA" }
}

internal class EcdsaP256Verifier(private val publicKey: PublicKey) : RestoreGuardianSignatureVerifier {
    init { requireP256(publicKey) }

    override fun verify(canonicalPayload: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance(ALGORITHM).run { initVerify(publicKey); update(canonicalPayload); verify(signature) }
    }.getOrDefault(false)

    private companion object { const val ALGORITHM = "SHA256withECDSA" }
}

internal object RestoreGuardianCrypto {
    fun signEnvelope(envelope: RestoreGuardianEnvelope, signer: RestoreGuardianSigner): SignedRestoreGuardianEnvelope =
        SignedRestoreGuardianEnvelope(envelope, signer.sign(envelope.canonicalBytes()))

    fun signHeartbeat(heartbeat: RestoreGuardianHeartbeat, signer: RestoreGuardianSigner): SignedRestoreGuardianHeartbeat =
        SignedRestoreGuardianHeartbeat(heartbeat, signer.sign(heartbeat.canonicalBytes()))

    fun signSessionPin(pin: RestoreGuardianSessionPin, signer: RestoreGuardianSigner): SignedRestoreGuardianSessionPin =
        SignedRestoreGuardianSessionPin(pin, signer.sign(pin.canonicalBytes()))

    fun signReadyReceipt(receipt: RestoreGuardianReadyReceipt, signer: GuardianSessionSigner): SignedRestoreGuardianReadyReceipt =
        SignedRestoreGuardianReadyReceipt(receipt, signer.sign(receipt.canonicalBytes()))

    fun signStockConfirmedReceipt(receipt: RestoreGuardianStockConfirmedReceipt, signer: GuardianSessionSigner) =
        SignedRestoreGuardianStockConfirmedReceipt(receipt, signer.sign(receipt.canonicalBytes()))

    fun signExitedReceipt(receipt: RestoreGuardianExitedReceipt, signer: GuardianSessionSigner) =
        SignedRestoreGuardianExitedReceipt(receipt, signer.sign(receipt.canonicalBytes()))

    fun verifyEnvelope(signed: SignedRestoreGuardianEnvelope, pinned: PinnedRestoreGuardianVerifier): Boolean =
        pinned.trustedFor(signed.envelope) && pinned.verifier().verify(signed.envelope.canonicalBytes(), signed.signature)

    fun verifyHeartbeat(
        signed: SignedRestoreGuardianHeartbeat,
        pinned: PinnedRestoreGuardianVerifier,
        envelope: RestoreGuardianEnvelope,
    ): Boolean =
        pinned.trustedFor(envelope) &&
            pinned.verifier().verify(signed.heartbeat.canonicalBytes(), signed.signature)

    fun verifySessionPin(signed: SignedRestoreGuardianSessionPin, pinned: PinnedRestoreGuardianVerifier, envelope: RestoreGuardianEnvelope): Boolean =
        pinned.trustedFor(envelope) && pinned.verifier().verify(signed.pin.canonicalBytes(), signed.signature)

    fun verifyReadyReceipt(signed: SignedRestoreGuardianReadyReceipt, envelope: RestoreGuardianEnvelope): Boolean =
        signed.receipt.sessionPublicKey.verifier().verify(signed.receipt.canonicalBytes(), signed.signature) && receiptMatches(envelope, signed.receipt)

    /**
     * Unchanged in meaning: this is exactly [stockConfirmedReceiptRefusal] returning nothing.
     *
     * Expressed through the diagnostic rather than beside it **on purpose**. Eleven comparisons used to
     * collapse into this one boolean, and on 2026-09-05 that boolean was the thing stranding a measured
     * Odin — `terminalDigest cause=stock_unverified detail=returned_false` said the receipt refused and
     * could say nothing else. A second, parallel copy of the conjunction would answer that question
     * until the day the two drifted, which is the same defect one level up. One expression, two callers.
     */
    fun verifyStockConfirmedReceipt(signed: SignedRestoreGuardianStockConfirmedReceipt, session: GuardianSessionPublicKey, envelope: RestoreGuardianEnvelope): Boolean =
        stockConfirmedReceiptRefusal(signed, session, envelope) == null

    /** Which clause of the stock-confirmed check refuses, or null when it verifies. Order is the check's own. */
    internal fun stockConfirmedReceiptRefusal(
        signed: SignedRestoreGuardianStockConfirmedReceipt,
        session: GuardianSessionPublicKey,
        envelope: RestoreGuardianEnvelope,
    ): String? = when {
        signed.receipt.sessionKeySha256 != session.sha256() -> "session_key"
        else -> terminalReceiptRefusal(
            envelope,
            signed.receipt.helperBuildIdentity,
            signed.receipt.bootIdentity,
            signed.receipt.generation,
            signed.receipt.requestId,
            signed.receipt.transactionId,
            signed.receipt.taskNonce,
            signed.receipt.envelopeSha256,
        ) ?: when {
            terminalScopeRefusal(
                envelope,
                signed.receipt.recoveryBootIdentity,
                signed.receipt.restoreScope,
                signed.receipt.verifiedStockEntrySetSha256,
            ) != null -> "scope:" + terminalScopeRefusal(
                envelope,
                signed.receipt.recoveryBootIdentity,
                signed.receipt.restoreScope,
                signed.receipt.verifiedStockEntrySetSha256,
            )
            !session.verifier().verify(signed.receipt.canonicalBytes(), signed.signature) -> "signature"
            else -> null
        }
    }

    fun verifyExitedReceipt(signed: SignedRestoreGuardianExitedReceipt, session: GuardianSessionPublicKey, envelope: RestoreGuardianEnvelope): Boolean =
        signed.receipt.sessionKeySha256 == session.sha256() &&
            terminalReceiptMatches(envelope, signed.receipt.helperBuildIdentity, signed.receipt.bootIdentity, signed.receipt.generation, signed.receipt.requestId, signed.receipt.transactionId, signed.receipt.taskNonce, signed.receipt.envelopeSha256) &&
            terminalScopeMatches(
                envelope,
                signed.receipt.recoveryBootIdentity,
                signed.receipt.restoreScope,
                signed.receipt.verifiedStockEntrySetSha256,
            ) &&
            session.verifier().verify(signed.receipt.canonicalBytes(), signed.signature)

    fun receiptMatches(envelope: RestoreGuardianEnvelope, receipt: RestoreGuardianReadyReceipt): Boolean =
        receipt.helperBuildIdentity == envelope.helperBuildIdentity &&
            receipt.bootIdentity == envelope.bootIdentity &&
            receipt.generation == envelope.generation &&
            receipt.requestId == envelope.requestId &&
            receipt.transactionId == envelope.transactionId &&
            receipt.taskNonce == envelope.taskNonce &&
            receipt.envelopeSha256 == envelope.digestSha256() &&
            receipt.issuedElapsedRealtimeMs <= envelope.hardDeadlineElapsedRealtimeMs

    fun terminalReceiptMatches(envelope: RestoreGuardianEnvelope, helperBuildIdentity: String, bootIdentity: String, generation: Long, requestId: String, transactionId: String, taskNonce: String, envelopeSha256: String): Boolean =
        terminalReceiptRefusal(envelope, helperBuildIdentity, bootIdentity, generation, requestId, transactionId, taskNonce, envelopeSha256) == null

    /** Which identity field of a terminal receipt disagrees with the envelope, or null when all agree. */
    internal fun terminalReceiptRefusal(envelope: RestoreGuardianEnvelope, helperBuildIdentity: String, bootIdentity: String, generation: Long, requestId: String, transactionId: String, taskNonce: String, envelopeSha256: String): String? = when {
        helperBuildIdentity != envelope.helperBuildIdentity -> "helper_build"
        bootIdentity != envelope.bootIdentity -> "boot_identity"
        generation != envelope.generation -> "generation"
        requestId != envelope.requestId -> "request_id"
        transactionId != envelope.transactionId -> "transaction_id"
        taskNonce != envelope.taskNonce -> "task_nonce"
        envelopeSha256 != envelope.digestSha256() -> "envelope_digest"
        else -> null
    }

    private fun terminalScopeMatches(
        envelope: RestoreGuardianEnvelope,
        recoveryBootIdentity: String,
        scope: GuardianTerminalRestoreScope,
        verifiedStockEntrySetSha256: String,
    ): Boolean = terminalScopeRefusal(envelope, recoveryBootIdentity, scope, verifiedStockEntrySetSha256) == null

    /** Which of the scope check's three refusals fired, or null when the scope is proof. */
    internal fun terminalScopeRefusal(
        envelope: RestoreGuardianEnvelope,
        recoveryBootIdentity: String,
        scope: GuardianTerminalRestoreScope,
        verifiedStockEntrySetSha256: String,
    ): String? {
        // Fixed 2026-09-05: this used to digest `envelope.restoreEntries` unconditionally, but the
        // receipt's digest (RestoreGuardianRunnerEngine.confirmStock) is taken over
        // `scope.eligibleRestoreEntries(...)`, which is narrower than the full set on a cross-boot
        // restore. Recompute over the same scope-filtered entries the producer used -- see
        // [eligibleRestoreEntries]'s KDoc for the device evidence.
        val eligible = scope.eligibleRestoreEntries(envelope.restoreEntries)
        // Empty-set fail-closed handling: fail-closed is the answer, and the signed receipt format is
        // NOT being extended to express "nothing was verifiable" -- see the empty-set fail-closed note
        // on RestoreGuardianRunnerEngine.confirmStock's empty-set branch. Both branches below still
        // refuse; only the NAME changes. Before this, an empty eligible set and a genuine digest
        // mismatch both returned "entry_set", so a device holding for "there was nothing on this
        // boot I was permitted to verify" was indistinguishable in every log from one holding
        // because two digests disagree -- only one of those is a bug.
        if (eligible.isEmpty()) {
            return "entry_set_empty"
        }
        if (verifiedStockEntrySetSha256 != GuardianRestoreEntrySetDigest.of(eligible)) {
            return "entry_set"
        }
        return when (scope) {
            GuardianTerminalRestoreScope.SAME_BOOT_FULL ->
                if (recoveryBootIdentity == envelope.bootIdentity) null else "same_boot_receipt_after_reboot"
            GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK ->
                // A CPU/GPU-only plan has no persistent axis to replay after reboot.
                // Its terminal proof is still meaningful only after a different boot
                // and exact double readback of the complete captured Stock entry set.
                if (recoveryBootIdentity != envelope.bootIdentity) null else "cross_boot_receipt_same_boot"
        }
    }
}

internal fun RestoreGuardianRestoreEntry.isPersistentGuardianAxis(): Boolean =
    this is RestoreGuardianFanPutEntry || this is RestoreGuardianFanDeleteEntry ||
        // The vendor performance mode outlives a reboot, so the cross-boot replay must cover it:
        // after a reboot nothing else will ever put the player's mode back.
        this is RestoreGuardianVendorModePutEntry

private fun requireP256(key: Any) {
    val ec = key as? ECKey ?: throw IllegalArgumentException("guardian key must be EC P-256")
    val params = ec.params ?: throw IllegalArgumentException("guardian EC parameters missing")
    val field = params.curve.field as? ECFieldFp
    require(
        field?.p == P256_FIELD_PRIME &&
            params.curve.a == P256_A &&
            params.curve.b == P256_B &&
            params.generator.affineX == P256_GENERATOR_X &&
            params.generator.affineY == P256_GENERATOR_Y &&
            params.order == P256_ORDER &&
            params.cofactor == P256_COFACTOR,
    ) {
        "guardian key must be P-256"
    }
}

private fun hex(value: String): BigInteger = BigInteger(value, 16)

private val P256_FIELD_PRIME = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
private val P256_A = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC")
private val P256_B = hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
private val P256_GENERATOR_X = hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
private val P256_GENERATOR_Y = hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")
private val P256_ORDER = hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
private const val P256_COFACTOR = 1
