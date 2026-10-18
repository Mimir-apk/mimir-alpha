package com.gruning.mimir.tweaker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

internal sealed interface GuardianProcessSessionKeyTruth {
    data class Available(
        val authority: GuardianProcessSessionAuthority,
        val publicKey: GuardianSessionPublicKey,
        val alias: String,
    ) : GuardianProcessSessionKeyTruth

    data object Missing : GuardianProcessSessionKeyTruth
    data class Unavailable(val reason: String) : GuardianProcessSessionKeyTruth
    data class Corrupt(val reason: String) : GuardianProcessSessionKeyTruth
}

/**
 * One non-exportable AndroidKeyStore P-256 receipt key per authenticated envelope. The alias is
 * derived from the envelope digest, so a stale key can never be silently adopted by a later arm.
 */
internal class AndroidGuardianProcessSessionKeyStore(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val keyStoreFactory: () -> KeyStore = { KeyStore.getInstance(ANDROID_KEYSTORE) },
    private val generatorFactory: () -> KeyPairGenerator = {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
    },
) {
    @Synchronized
    fun loadOrCreate(
        envelope: RestoreGuardianEnvelope,
        expectedPublicKey: GuardianSessionPublicKey?,
    ): GuardianProcessSessionKeyTruth {
        val alias = alias(envelope)
        val keyStore = runCatching { keyStoreFactory().also { it.load(null) } }
            .getOrElse { return GuardianProcessSessionKeyTruth.Unavailable("AndroidKeyStore unavailable: ${it.message}") }
        if (keyStore.provider.name != ANDROID_KEYSTORE) {
            return GuardianProcessSessionKeyTruth.Corrupt("wrong AndroidKeyStore provider")
        }
        val present = runCatching { keyStore.containsAlias(alias) }
            .getOrElse { return GuardianProcessSessionKeyTruth.Unavailable("session key query failed: ${it.message}") }
        if (!present) {
            if (expectedPublicKey != null) return GuardianProcessSessionKeyTruth.Missing
            val generated = runCatching {
                generatorFactory().apply {
                    initialize(
                        KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                        )
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setUserAuthenticationRequired(false)
                            .setUnlockedDeviceRequired(false)
                            .build(),
                    )
                }.generateKeyPair()
            }.getOrElse {
                return GuardianProcessSessionKeyTruth.Unavailable("session key generation failed: ${it.message}")
            }
            return available(alias, generated, expectedPublicKey)
        }
        val entry = runCatching { keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry }
            .getOrElse { return GuardianProcessSessionKeyTruth.Unavailable("session key load failed: ${it.message}") }
            ?: return GuardianProcessSessionKeyTruth.Corrupt("session alias is not a private key")
        return available(alias, KeyPair(entry.certificate.publicKey, entry.privateKey), expectedPublicKey)
    }

    @Synchronized
    fun deleteExpected(
        envelope: RestoreGuardianEnvelope,
        expectedPublicKey: GuardianSessionPublicKey,
    ): Boolean {
        val alias = alias(envelope)
        val keyStore = runCatching { keyStoreFactory().also { it.load(null) } }.getOrNull() ?: return false
        val entry = runCatching { keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
            ?: return !runCatching { keyStore.containsAlias(alias) }.getOrDefault(true)
        val actual = runCatching { GuardianSessionPublicKey.fromPublicKey(entry.certificate.publicKey) }.getOrNull()
            ?: return false
        if (actual != expectedPublicKey) return false
        return runCatching {
            keyStore.deleteEntry(alias)
            !keyStore.containsAlias(alias)
        }.getOrDefault(false)
    }

    internal fun alias(envelope: RestoreGuardianEnvelope): String =
        "$ALIAS_PREFIX.${envelope.digestSha256()}"

    private fun available(
        alias: String,
        pair: KeyPair,
        expectedPublicKey: GuardianSessionPublicKey?,
    ): GuardianProcessSessionKeyTruth {
        val authority = GuardianProcessSessionAuthority.fromKeyPair(pair)
            ?: return GuardianProcessSessionKeyTruth.Corrupt("session key rejected")
        val publicKey = authority.sessionPublicKey
        if (expectedPublicKey != null && publicKey != expectedPublicKey) {
            authority.close()
            return GuardianProcessSessionKeyTruth.Corrupt("session public key mismatch")
        }
        return GuardianProcessSessionKeyTruth.Available(authority, publicKey, alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "com.gruning.mimir.tweaker.guardian-session.p256.v1"
    }
}
