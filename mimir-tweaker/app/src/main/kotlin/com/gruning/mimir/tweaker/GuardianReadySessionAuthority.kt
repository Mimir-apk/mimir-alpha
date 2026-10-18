package com.gruning.mimir.tweaker

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.security.auth.Destroyable

/**
 * Process-scoped receipt-direction authority for one guardian READY publication.
 *
 * This is deliberately pure and unwired: it owns no transport, durable store, controller,
 * lifecycle, or hardware authority. The private key is never exposed, encoded, or persisted.
 * Process death remains the authoritative destruction boundary; [close] only makes this
 * in-process object fail closed and makes a best-effort Destroyable request when supported.
 */
internal class GuardianReadySessionAuthority private constructor(
    private var keyPair: KeyPair?,
    private val pinnedSessionPublicKey: GuardianSessionPublicKey,
) : GuardianReceiptAuthenticationStrategy, AutoCloseable {

    /** Immutable public identity remains defensively available after local signing retirement. */
    val sessionPublicKey: GuardianSessionPublicKey
        get() = GuardianSessionPublicKey.fromSec1(pinnedSessionPublicKey.sec1Bytes())

    /** READY-only proof-of-possession. Terminal receipt types intentionally remain unsupported. */
    @Synchronized
    override fun authenticate(type: GuardianReceiptType, canonicalPayload: ByteArray): ByteArray? {
        if (type != GuardianReceiptType.READY || canonicalPayload.size !in 1..GuardianArmRecordCodec.MAX_READY) return null
        val activePair = keyPair ?: return null
        return runCatching {
            EcdsaP256GuardianSessionSigner(activePair.private).sign(canonicalPayload)
                .takeIf { it.size in 1..GuardianArmRecordCodec.MAX_SIGNATURE }
        }.getOrNull()
    }

    /** Idempotent, fail-closed local retirement; no serialized private-key representation exists. */
    @Synchronized
    override fun close() {
        val retired = keyPair ?: return
        keyPair = null
        (retired.private as? Destroyable)?.let { destroyable ->
            runCatching {
                if (!destroyable.isDestroyed) destroyable.destroy()
            }
        }
    }

    companion object {
        /**
         * Total generator seam for host tests. Any factory, initialization, pair, or P-256
         * validation failure returns null and leaves no usable authority.
         */
        fun generate(generator: () -> KeyPairGenerator): GuardianReadySessionAuthority? = runCatching {
            val keyPairGenerator = generator()
            keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = keyPairGenerator.generateKeyPair()
            val sessionKey = GuardianSessionPublicKey.fromPublicKey(pair.public)
            EcdsaP256GuardianSessionSigner(pair.private) // validates the private key before ownership.
            GuardianReadySessionAuthority(pair, sessionKey)
        }.getOrNull()
    }
}
