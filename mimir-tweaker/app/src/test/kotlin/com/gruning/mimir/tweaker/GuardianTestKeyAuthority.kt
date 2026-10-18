package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.mgde.MgdeAdmissionConsumePort
import com.gruning.mimir.tweaker.mgde.UnavailableMgdeAdmissionConsumePort
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.interfaces.ECKey
import java.security.interfaces.ECPrivateKey
import java.util.Date
import java.util.Enumeration
import java.util.Collections

/**
 * Test-only AndroidKeyStore-shaped harness. It reaches the opaque [DirectBootKeyAuthorityTruth.Available] exclusively
 * through [DirectBootKeyAuthority.inspect]; tests cannot mint a capability directly.
 */
internal class GuardianTestKeyAuthority private constructor(
    val available: DirectBootKeyAuthorityTruth.Available,
) {
    companion object {
        fun create(
            certificateDigest: String = digest("certificate"),
            pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair(),
            certificatePublicKey: PublicKey = pair.public,
        ): GuardianTestKeyAuthority {
            // Explicitly test-only reflection. Production has no injectable authority
            // constructor; this harness creates a synthetic operation authority without making
            // an AndroidKeyStore/direct-boot claim.
            val identity = sha256(certificatePublicKey.encoded)
            val capabilityType = DirectBootKeyAuthorityTruth.Available::class.java.declaredClasses
                .single { it.simpleName == "Capability" }
            val capability = capabilityType.getDeclaredConstructor(String::class.java, String::class.java)
                .also { it.isAccessible = true }
                .newInstance(identity, certificateDigest)
            val constructor = DirectBootKeyAuthorityTruth.Available::class.java.declaredConstructors
                .single { !it.isSynthetic }
                .also { it.isAccessible = true }
            val available = constructor.newInstance(
                EcdsaP256Signer(pair.private),
                certificatePublicKey,
                identity,
                certificateDigest,
                capability,
            ) as DirectBootKeyAuthorityTruth.Available
            return GuardianTestKeyAuthority(available)
        }

        fun inspect(
            certificateDigest: String = digest("certificate"),
            pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair(),
            certificatePublicKey: PublicKey = pair.public,
            metadataPublicKeyDigest: String = sha256(certificatePublicKey.encoded),
            providerName: String = "AndroidKeyStore",
            exportablePrivateKey: Boolean = false,
        ): DirectBootKeyAuthorityTruth {
            val directory = kotlin.io.path.createTempDirectory("guardian-test-authority-").toFile()
            val metadata = DirectBootKeyMetadata(
                alias = DirectBootKeyAuthority.KEY_ALIAS,
                publicKeySha256 = metadataPublicKeyDigest,
                appSigningCertificateSha256 = certificateDigest,
            )
            val metadataStore = DirectBootKeyMetadataStore(directory, DirectBootMetadataDirectorySyncer { true })
            check(metadataStore.writeFresh(metadata))
            val keyStore = FakeAndroidKeyStore(
                DirectBootKeyAuthority.KEY_ALIAS,
                KeyStore.PrivateKeyEntry(if (exportablePrivateKey) pair.private else NonExportableTestPrivateKey(pair.private), arrayOf(TestCertificate(certificatePublicKey))),
                providerName,
            )
            val directBoot = reflectedDirectBootKeyAuthority(
                metadataStore,
                AppSigningCertificateDigestSource { certificateDigest },
                { keyStore },
                { error("test authority must inspect, never provision") },
            )
            return directBoot.inspect()
        }

        private fun digest(value: String): String = sha256(value.toByteArray())
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

internal fun reflectedDirectBootKeyAuthority(
    metadataStore: DirectBootKeyMetadataStore,
    certificateDigestSource: AppSigningCertificateDigestSource,
    keyStoreFactory: () -> KeyStore,
    keyPairGeneratorFactory: () -> KeyPairGenerator,
    mgdeConsumePort: MgdeAdmissionConsumePort = UnavailableMgdeAdmissionConsumePort,
): DirectBootKeyAuthority {
    val constructor = DirectBootKeyAuthority::class.java.declaredConstructors
        .single { it.parameterCount == 5 }
        .also { it.isAccessible = true }
    return constructor.newInstance(
        metadataStore,
        certificateDigestSource,
        keyStoreFactory,
        keyPairGeneratorFactory,
        mgdeConsumePort,
    ) as DirectBootKeyAuthority
}

internal fun guardianFakeAndroidKeyStore(
    pair: KeyPair? = null,
    certificatePublicKey: PublicKey? = pair?.public,
    entryIsPrivate: Boolean = true,
    exportablePrivateKey: Boolean = false,
    providerName: String = "AndroidKeyStore",
    loadFailure: Boolean = false,
    containsFailure: Boolean = false,
): KeyStore {
    val entry: KeyStore.Entry? = when {
        pair == null || certificatePublicKey == null -> null
        !entryIsPrivate -> KeyStore.TrustedCertificateEntry(TestCertificate(certificatePublicKey))
        else -> {
            val privateKey = when {
                exportablePrivateKey -> pair.private
                pair.private is ECPrivateKey -> NonExportableTestPrivateKey(pair.private)
                else -> NonExportableGenericPrivateKey(pair.private)
            }
            KeyStore.PrivateKeyEntry(privateKey, arrayOf(TestCertificate(certificatePublicKey)))
        }
    }
    return FakeAndroidKeyStore(
        DirectBootKeyAuthority.KEY_ALIAS,
        entry,
        providerName,
        loadFailure,
        containsFailure,
    )
}

private class FakeAndroidKeyStore(
    alias: String,
    entry: KeyStore.Entry?,
    providerName: String,
    loadFailure: Boolean = false,
    containsFailure: Boolean = false,
) : KeyStore(
    FakeAndroidKeyStoreSpi(alias, entry, loadFailure, containsFailure),
    FakeAndroidKeyStoreProvider(providerName),
    "AndroidKeyStore",
)

private class FakeAndroidKeyStoreProvider(name: String) : Provider(name, 1.0, "test-only AndroidKeyStore")

private class FakeAndroidKeyStoreSpi(
    private val alias: String,
    private val entry: KeyStore.Entry?,
    private val loadFailure: Boolean,
    private val containsFailure: Boolean,
) : KeyStoreSpi() {
    private val privateEntry get() = entry as? KeyStore.PrivateKeyEntry
    private val trustedEntry get() = entry as? KeyStore.TrustedCertificateEntry
    override fun engineGetKey(alias: String, password: CharArray?): Key? = if (alias == this.alias) privateEntry?.privateKey else null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = if (alias == this.alias) privateEntry?.certificateChain else null
    override fun engineGetCertificate(alias: String): Certificate? =
        if (alias == this.alias) privateEntry?.certificate ?: trustedEntry?.trustedCertificate else null
    override fun engineGetCreationDate(alias: String): Date? = if (alias == this.alias) Date(0) else null
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>) = unsupported()
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>) = unsupported()
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = unsupported()
    override fun engineDeleteEntry(alias: String) = unsupported()
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(if (entry == null) emptyList() else listOf(alias))
    override fun engineContainsAlias(alias: String): Boolean {
        if (containsFailure) error("test contains failure")
        return entry != null && alias == this.alias
    }
    override fun engineSize(): Int = if (entry == null) 0 else 1
    override fun engineIsKeyEntry(alias: String): Boolean = alias == this.alias && privateEntry != null
    override fun engineIsCertificateEntry(alias: String): Boolean = alias == this.alias && trustedEntry != null
    override fun engineGetCertificateAlias(cert: Certificate): String? =
        if (cert == privateEntry?.certificate || cert == trustedEntry?.trustedCertificate) alias else null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = unsupported()
    override fun engineLoad(stream: InputStream?, password: CharArray?) {
        if (loadFailure) error("test load failure")
    }
    override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? = if (alias == this.alias) entry else null
    override fun engineEntryInstanceOf(alias: String, entryClass: Class<out KeyStore.Entry>): Boolean =
        alias == this.alias && entryClass.isInstance(entry)

    private fun unsupported(): Nothing = throw UnsupportedOperationException("test-only read-only keystore")
}

private class TestCertificate(private val publicKey: PublicKey) : Certificate("X.509") {
    override fun getEncoded(): ByteArray = publicKey.encoded.copyOf()
    override fun verify(key: PublicKey) = Unit
    override fun verify(key: PublicKey, sigProvider: String) = Unit
    override fun toString(): String = "Guardian test certificate"
    override fun getPublicKey(): PublicKey = publicKey
}

private class NonExportableTestPrivateKey(private val delegate: PrivateKey) : ECPrivateKey {
    private val ec = delegate as? ECPrivateKey ?: error("test key is not EC private")
    override fun getAlgorithm(): String = delegate.algorithm
    override fun getFormat(): String? = delegate.format
    override fun getEncoded(): ByteArray? = null
    override fun getParams() = ec.params
    override fun getS() = ec.s
}

private class NonExportableGenericPrivateKey(private val delegate: PrivateKey) : PrivateKey {
    override fun getAlgorithm(): String = delegate.algorithm
    override fun getFormat(): String? = delegate.format
    override fun getEncoded(): ByteArray? = null
}
