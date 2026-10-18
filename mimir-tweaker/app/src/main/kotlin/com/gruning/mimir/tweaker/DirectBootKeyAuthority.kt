package com.gruning.mimir.tweaker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.math.BigInteger
import com.gruning.mimir.tweaker.mgde.MgdeAdmission
import com.gruning.mimir.tweaker.mgde.MgdeAdmissionConsumePort
import com.gruning.mimir.tweaker.mgde.MgdeAdmissionResult
import com.gruning.mimir.tweaker.mgde.MgdeExpectedIdentity
import com.gruning.mimir.tweaker.mgde.MgdeExpectedStockProjection
import com.gruning.mimir.tweaker.mgde.MgdeObservedSource
import com.gruning.mimir.tweaker.mgde.MgdeRejection
import com.gruning.mimir.tweaker.mgde.MgdeV2Codec
import com.gruning.mimir.tweaker.mgde.MgdeV2Record
import com.gruning.mimir.tweaker.mgde.UnavailableMgdeAdmissionConsumePort
import com.gruning.mimir.tweaker.mgde.MgdeExpectedStockProjection.Companion.fromAuthenticatedJournal
import java.security.interfaces.ECKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec

/**
 * Per-install signing-key authority for authenticated recovery records.
 *
 * This is deliberately not an attestation verifier and does not make a hardware-backed-key
 * claim. The public-key digest may be pinned later by the PServer launch channel; until then a
 * caller must not mistake this app-origin provenance for guardian attestation.
 */
internal sealed interface DirectBootKeyAuthorityTruth {
    /** Neither the fixed alias nor metadata exists; provisioning exactly once is permitted. */
    data object Fresh : DirectBootKeyAuthorityTruth

    /**
     * A proven app-origin capability. This is deliberately not guardian/channel/hardware
     * attestation. Its constructor and all key material are private: the nested authority is the
     * only source-level minter and validates AndroidKeyStore plus the installed certificate
     * before it can create this object.
     */
    class Available private constructor(
        private val signer: RestoreGuardianSigner,
        private val publicKey: PublicKey,
        private val keyIdentitySha256: String,
        private val appSigningCertificateSha256: String,
        private val capability: Capability,
    ) : DirectBootKeyAuthorityTruth {
        /**
         * Compile-time capability token. Kotlin requires this type to be internal because it
         * appears in the two internal pinned-operation constructors; its only constructor is
         * private and [Minter] is its sole source. No declaration outside [Available] returns or
         * stores a token.
         */
        internal class Capability private constructor(
            private val keyIdentitySha256: String,
            private val appSigningCertificateSha256: String,
        ) {
            internal fun requireEnvelopeBinding(
                candidateKey: PublicKey,
                candidateCertificate: String,
            ) {
                require(sha256(candidateKey.encoded) == keyIdentitySha256) {
                    "capability public-key binding mismatch"
                }
                require(candidateCertificate == appSigningCertificateSha256) {
                    "capability certificate binding mismatch"
                }
            }

            internal fun requireJournalBinding(
                candidateIdentity: String,
                candidateKey: PublicKey,
                candidateCertificate: String,
            ) {
                require(candidateIdentity == keyIdentitySha256) {
                    "capability key-identity binding mismatch"
                }
                requireEnvelopeBinding(candidateKey, candidateCertificate)
            }
        }

        /** Opaque envelope verifier; construction never accepts raw key/certificate input. */
        internal fun envelopeVerifier(): PinnedRestoreGuardianVerifier =
            verifierFor(capability)

        /** Opaque MGDE admission verification; callers never receive the key, certificate, or verifier. */
        private fun verifiesMgde(record: MgdeV2Record): Boolean = runCatching {
            val envelope = record.decodedEnvelope
            require(envelope.canonicalBytes().contentEquals(record.envelope) && envelope.tweakerCertificateSha256 == appSigningCertificateSha256)
            EcdsaP256Verifier(publicKey).verify(record.envelope, record.signature)
        }.getOrDefault(false)

        /** Opaque journal verifier; construction never accepts raw key/certificate input. */
        internal fun journalVerifier(): PinnedTransactionJournalVerifier =
            journalFor(capability)

        private fun verifierFor(capability: Capability): PinnedRestoreGuardianVerifier =
            PinnedRestoreGuardianVerifier(publicKey, appSigningCertificateSha256, capability)

        private fun journalFor(capability: Capability): PinnedTransactionJournalVerifier =
            PinnedTransactionJournalVerifier(keyIdentitySha256, publicKey, appSigningCertificateSha256, capability)

        /** The only app-origin journal-signing operation; no caller-supplied authenticator. */
        internal fun signJournal(encodedJournalPayload: ByteArray): SignedTransactionJournalRecord {
            require(encodedJournalPayload.size in 1..(256 * 1024)) { "journal payload size invalid" }
            val decoded = AtomicFileTransactionJournal.decodeJournal(encodedJournalPayload.toString(Charsets.UTF_8))
            require(decoded is JournalLoadResult.Loaded) { "cannot sign invalid journal payload" }
            val schema = decoded.record.schemaVersion
            require(
                schema == TRANSACTION_JOURNAL_SCHEMA ||
                    schema == TRANSACTION_JOURNAL_SCHEMA_V5 ||
                    schema == TRANSACTION_JOURNAL_SCHEMA_V3 ||
                    schema == TRANSACTION_JOURNAL_SCHEMA_V2,
            ) { "legacy journal cannot be authenticated" }
            val signature = signer.sign(
                AuthenticatedTransactionJournalCodec.canonicalBytes(
                    schema,
                    keyIdentitySha256,
                    encodedJournalPayload,
                ),
            )
            return SignedTransactionJournalRecord(
                schema,
                keyIdentitySha256,
                encodedJournalPayload.copyOf(),
                signature,
            )
        }

        /** Domain-separated app-origin signature for the user-unlock GameManager recovery journal. */
        internal fun signFpsJournal(encodedJournalPayload: ByteArray): SignedFpsLimiterJournalRecord {
            require(encodedJournalPayload.size in 1..FpsLimiterJournalCodec.MAX_PAYLOAD_BYTES) {
                "FPS journal payload size invalid"
            }
            require(FpsLimiterJournalCodec.decodePayload(encodedJournalPayload) != null) {
                "cannot sign invalid FPS journal payload"
            }
            val signature = signer.sign(
                FpsLimiterJournalCodec.canonicalBytes(keyIdentitySha256, encodedJournalPayload),
            )
            return SignedFpsLimiterJournalRecord(
                keyIdentity = keyIdentitySha256,
                encodedPayload = encodedJournalPayload.copyOf(),
                signature = signature,
            )
        }

        /** Verification stays inside the minted authority; raw key material never crosses this seam. */
        internal fun verifiesFpsJournal(record: SignedFpsLimiterJournalRecord): Boolean = runCatching {
            require(record.keyIdentity == keyIdentitySha256)
            EcdsaP256Verifier(publicKey).verify(record.canonicalBytes(), record.signature)
        }.getOrDefault(false)

        /** Opaque app-origin envelope verifier; only [Available] can create one. */
        class PinnedRestoreGuardianVerifier internal constructor(
            private val publicKey: PublicKey,
            private val pinnedTweakerCertificateSha256: String,
            @Suppress("UNUSED_PARAMETER") capability: Capability,
        ) {
            init {
                GuardianEnvelopeValidation.requireSha256(pinnedTweakerCertificateSha256, "pinned certificate")
                capability.requireEnvelopeBinding(publicKey, pinnedTweakerCertificateSha256)
            }

            fun trustedFor(envelope: RestoreGuardianEnvelope): Boolean =
                pinnedTweakerCertificateSha256 == envelope.tweakerCertificateSha256

            fun verifier(): RestoreGuardianSignatureVerifier = EcdsaP256Verifier(publicKey)

            internal fun writeArmRecordBinding(out: DataOutputStream) {
                val encoded = publicKey.encoded ?: error("key encoding absent")
                out.writeInt(encoded.size)
                out.write(encoded)
                val certificate = pinnedTweakerCertificateSha256.toByteArray(Charsets.UTF_8)
                out.writeInt(certificate.size)
                out.write(certificate)
            }

            internal fun matchesArmRecordBinding(storedKey: ByteArray, certificate: String): Boolean {
                val encoded = publicKey.encoded ?: return false
                return encoded.contentEquals(storedKey) && pinnedTweakerCertificateSha256 == certificate
            }

            internal fun matchesSessionSec1(candidate: ByteArray): Boolean = runCatching {
                GuardianSessionPublicKey.fromPublicKey(publicKey).sec1Bytes().contentEquals(candidate)
            }.getOrDefault(false)
        }

        /** Opaque app-origin journal verifier; only [Available] can create one. */
        class PinnedTransactionJournalVerifier internal constructor(
            private val keyIdentity: String,
            private val publicKey: PublicKey,
            private val pinnedTweakerCertificateSha256: String,
            @Suppress("UNUSED_PARAMETER") capability: Capability,
        ) {
            init {
                JournalAuthenticationValidation.requireKeyIdentity(keyIdentity)
                GuardianEnvelopeValidation.requireSha256(pinnedTweakerCertificateSha256, "pinned certificate")
                capability.requireJournalBinding(keyIdentity, publicKey, pinnedTweakerCertificateSha256)
            }

            fun verifier(): TransactionJournalAuthenticationVerifier =
                TransactionJournalAuthenticationVerifier { bytes, signature -> EcdsaP256Verifier(publicKey).verify(bytes, signature) }

            internal fun matchesKeyIdentity(candidate: String): Boolean = keyIdentity == candidate

        }

        /** App-origin signing is limited to the typed envelope/liveness/session-pin protocol. */
        internal fun signEnvelope(envelope: RestoreGuardianEnvelope): SignedRestoreGuardianEnvelope =
            RestoreGuardianCrypto.signEnvelope(envelope, signer)

        internal fun signHeartbeat(heartbeat: RestoreGuardianHeartbeat): SignedRestoreGuardianHeartbeat =
            RestoreGuardianCrypto.signHeartbeat(heartbeat, signer)

        internal fun signSessionPin(pin: RestoreGuardianSessionPin): SignedRestoreGuardianSessionPin =
            RestoreGuardianCrypto.signSessionPin(pin, signer)

        /**
         * Nested so this is the only source-visible construction path for [Available]. The
         * enclosing authority calls it only after its full metadata/key validation succeeds.
         */
        private object Minter {
            /*
             * Kotlin does not grant an enclosing class access to a nested class's private
             * constructor. The binding contract nevertheless requires exactly one PRIVATE
             * Capability constructor, no companion/factory on Capability, and a private sole
             * minter. Owner-class reflection is therefore deliberately confined here. This is
             * only a compile-time unforgeability boundary; reflection is explicitly outside the
             * claim.
             */
            private fun newCapability(
                keyIdentitySha256: String,
                appSigningCertificateSha256: String,
            ): Capability = Capability::class.java
                .getDeclaredConstructor(String::class.java, String::class.java)
                .also { it.isAccessible = true }
                .newInstance(keyIdentitySha256, appSigningCertificateSha256)

            fun mint(
                signer: RestoreGuardianSigner,
                publicKey: PublicKey,
                keyIdentitySha256: String,
                appSigningCertificateSha256: String,
            ): Available = Available(
                signer,
                publicKey,
                keyIdentitySha256,
                appSigningCertificateSha256,
                newCapability(keyIdentitySha256, appSigningCertificateSha256),
            )
        }

        /**
         * The direct-boot authority is nested under [Available] specifically so it can reach the
         * private minter but no same-module consumer can name a broader mint operation.
         */
        internal class DirectBootKeyAuthority private constructor(
            private val metadataStore: DirectBootKeyMetadataStore,
            private val certificateDigestSource: AppSigningCertificateDigestSource,
            private val keyStoreFactory: () -> KeyStore,
            private val keyPairGeneratorFactory: () -> KeyPairGenerator,
            private val mgdeConsumePort: MgdeAdmissionConsumePort,
        ) {
            internal constructor(context: Context) : this(
                metadataStore = DirectBootKeyMetadataStore(context),
                certificateDigestSource = AndroidAppSigningCertificateDigestSource(context),
                keyStoreFactory = { KeyStore.getInstance(ANDROID_KEYSTORE) },
                keyPairGeneratorFactory = { KeyPairGenerator.getInstance("EC", ANDROID_KEYSTORE) },
                mgdeConsumePort = UnavailableMgdeAdmissionConsumePort,
            )

            private val transitionLock = Any()
            /** Read-only state inspection. [DirectBootKeyAuthorityTruth.Fresh] never creates a key. */
            fun inspect(): DirectBootKeyAuthorityTruth = synchronized(transitionLock) { inspectLocked() }

            /**
             * The sole MGDE admission boundary. Fresh inspection is deliberately before strict
             * decoding, and neither a verifier nor an acceptance boolean crosses this seam.
             */
            internal fun admitMgde(
                source: MgdeObservedSource,
                identity: MgdeExpectedIdentity,
                authenticatedJournalBytes: ByteArray,
                liveCapabilities: HardwareCapabilities,
            ): MgdeAdmissionResult = synchronized(transitionLock) {
                // Direct-boot is an explicit caller-independent posture observation and blocks
                // before record bytes are touched.
                if (!identity.observedNoDirectBootClaim) return@synchronized MgdeAdmissionResult.Rejected(
                    MgdeRejection.PRE_UNLOCK, "PRE_UNLOCK_BLOCKED reason=not_observed_no_direct_boot_claim"
                )
                val available = inspectLocked() as? Available
                    ?: return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
                if (source === MgdeObservedSource.Absent) {
                    return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.ABSENT)
                }
                val rawRecord = source.attestedRecord()
                    ?: return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
                val record = MgdeV2Codec.decode(rawRecord)
                    ?: return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
                MgdeAdmission.precheck(source, record, identity)?.let {
                    return@synchronized it
                }
                if (!available.verifiesMgde(record)) {
                    return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
                }
                val journal = AuthenticatedTransactionJournalCodec.decodeAndVerify(
                    authenticatedJournalBytes,
                    TransactionJournalExpectedIdentity(identity.bootIdentity, identity.generation, identity.requestId, identity.transactionId),
                    available.journalVerifier(),
                ) as? JournalLoadResult.Loaded ?: return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
                val expectedStock = fromAuthenticatedJournal(journal.record, liveCapabilities, identity)
                    ?: return@synchronized MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
                MgdeAdmission.finish(record, mgdeConsumePort, identity, expectedStock)
            }

            private fun inspectLocked(): DirectBootKeyAuthorityTruth {
                val appDigest = runCatching { certificateDigestSource.currentDigest().also { requireSha256(it, "app certificate digest") } }
                    .getOrElse { return Unavailable("app certificate unavailable: ${it.message}") }
                val metadataResult = runCatching { metadataStore.load() }
                val metadata = metadataResult.getOrElse { return Corrupt("metadata unreadable: ${it.message}") }
                if (metadataStore.hasStagingRecord()) return Corrupt("metadata staging record exists")
                val keyStore = runCatching { keyStoreFactory().also { it.load(null) } }
                    .getOrElse { return Unavailable("AndroidKeyStore unavailable: ${it.message}") }
                if (keyStore.provider.name != ANDROID_KEYSTORE) return Corrupt("wrong keystore provider")
                val hasAlias = runCatching { keyStore.containsAlias(KEY_ALIAS) }
                    .getOrElse { return Unavailable("AndroidKeyStore query failed: ${it.message}") }

                if (metadata == null && !hasAlias) return Fresh
                if (metadata == null) return Corrupt("key exists without metadata")
                if (!hasAlias) return Corrupt("metadata exists without key")
                if (metadata.appSigningCertificateSha256 != appDigest) {
                    return CertificateChanged("app signing certificate changed")
                }
                return availableFrom(keyStore, metadata)
            }

            fun provisionFresh(): DirectBootKeyAuthorityTruth = synchronized(transitionLock) {
                val existing = inspectLocked()
                if (existing !is Fresh) return existing
                inspectOrProvisionFresh()
            }

            private fun inspectOrProvisionFresh(): DirectBootKeyAuthorityTruth {
                val appDigest = runCatching { certificateDigestSource.currentDigest().also { requireSha256(it, "app certificate digest") } }
                    .getOrElse { return Unavailable("app certificate unavailable: ${it.message}") }
                val metadata = runCatching { metadataStore.load() }
                    .getOrElse { return Corrupt("metadata unreadable: ${it.message}") }
                if (metadataStore.hasStagingRecord()) return Corrupt("metadata staging record exists")
                val keyStore = runCatching { keyStoreFactory().also { it.load(null) } }
                    .getOrElse { return Unavailable("AndroidKeyStore unavailable: ${it.message}") }
                if (keyStore.provider.name != ANDROID_KEYSTORE) return Corrupt("wrong keystore provider")
                val hasAlias = runCatching { keyStore.containsAlias(KEY_ALIAS) }
                    .getOrElse { return Unavailable("AndroidKeyStore query failed: ${it.message}") }
                if (metadata != null || hasAlias) return inspectLocked()
                return provisionFresh(keyStore, appDigest)
            }

            private fun provisionFresh(keyStore: KeyStore, appDigest: String): DirectBootKeyAuthorityTruth {
                val pair = runCatching {
                    keyPairGeneratorFactory().apply {
                        initialize(
                            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .setUserAuthenticationRequired(false)
                                .setUnlockedDeviceRequired(false)
                                .build(),
                        )
                    }.generateKeyPair()
                }.getOrElse { return Unavailable("key provisioning failed: ${it.message}") }
                val publicDigest = runCatching { sha256(pair.public.encoded) }
                    .getOrElse { return Corrupt("generated public key invalid: ${it.message}") }
                val metadata = runCatching { DirectBootKeyMetadata(KEY_ALIAS, publicDigest, appDigest) }
                    .getOrElse { return Corrupt("generated metadata invalid: ${it.message}") }
                if (!metadataStore.writeFresh(metadata)) return Unavailable("metadata persistence failed")
                return availableFrom(keyStore, metadata)
            }

            private fun availableFrom(keyStore: KeyStore, metadata: DirectBootKeyMetadata): DirectBootKeyAuthorityTruth = runCatching {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                    ?: error("key is not a private key entry")
                val privateKey = entry.privateKey
                require(privateKey.encoded == null) { "private key is exportable" }
                require(privateKey.algorithm == "EC") { "private key algorithm invalid" }
                requireP256(privateKey)
                val publicKey = entry.certificate.publicKey
                require(publicKey.algorithm == "EC") { "public key algorithm invalid" }
                requireP256(publicKey)
                val digest = sha256(publicKey.encoded)
                require(digest == metadata.publicKeySha256) { "public key digest mismatch" }
                val signer = EcdsaP256Signer(privateKey)
                val bindingChallenge = "MIMIR_APP_KEY_PROVENANCE_BINDING_V1".toByteArray(Charsets.UTF_8)
                require(EcdsaP256Verifier(publicKey).verify(bindingChallenge, signer.sign(bindingChallenge))) {
                    "private/public key pair mismatch"
                }
                Minter.mint(signer, publicKey, digest, metadata.appSigningCertificateSha256)
            }.getOrElse { Corrupt("key authority rejected: ${it.message}") }

            companion object {
                const val KEY_ALIAS = "com.gruning.mimir.tweaker.restore-guardian.ec-p256.v1"
                private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            }
        }
    }

    /** The platform could not be queried or provisioned. Existing state is intentionally kept. */
    data class Unavailable(val reason: String) : DirectBootKeyAuthorityTruth

    /** Existing key/metadata truth disagrees or is malformed. Existing state is intentionally kept. */
    data class Corrupt(val reason: String) : DirectBootKeyAuthorityTruth

    /**
     * The installed app's signing certificate no longer matches the one pinned into metadata at
     * first provisioning. This is deliberately its own disposition, not [Corrupt]: re-signing can change the installed certificate without corrupting stored key material -- unlike genuine corruption, it does not indicate anything is wrong with the stored
     * key/metadata pair, only that a different certificate produced this install. The binding
     * itself is unchanged and unweakened: metadata is still write-once, so a record still cannot
     * be read back under a certificate other than the one that armed it.
     *
     * Before this type existed, this case returned `Corrupt("app signing certificate changed")`,
     * which every downstream surface (the journal, the arm store, the `truth=` boot log) reported
     * as "corrupt" indistinguishably from a genuine metadata/key inconsistency. A re-signed
     * install then stranded any in-flight record with no operator-visible cause -- the log said
     * "corrupt", not "you re-signed". Existing state is intentionally kept, exactly as [Corrupt]
     * keeps it: this type changes what callers may SAY about the refusal, never whether it
     * refuses.
     */
    data class CertificateChanged(val reason: String) : DirectBootKeyAuthorityTruth
}

internal fun interface AppSigningCertificateDigestSource {
    /** Lowercase SHA-256 digest of the installed APK signing certificate set. */
    fun currentDigest(): String
}

internal class AndroidAppSigningCertificateDigestSource(
    private val context: Context,
) : AppSigningCertificateDigestSource {
    override fun currentDigest(): String {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        require(signers.isNotEmpty()) { "installed app signing certificate missing" }
        return canonicalAppSigningCertificateSetDigest(signers.map { it.toByteArray() })
    }
}

/** Canonical, order-independent identity for an APK signing certificate set. */
internal fun canonicalAppSigningCertificateSetDigest(certificates: List<ByteArray>): String {
    require(certificates.isNotEmpty()) { "installed app signing certificate missing" }
    val encoded = certificates.sortedWith(byteArrayComparator())
    return ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            encoded.forEach { certificate ->
                output.writeInt(certificate.size)
                output.write(certificate)
            }
        }
    }.toByteArray().let(::sha256)
}

/** Strict public-only device-protected metadata; it contains no private material or attestation verdict. */
internal data class DirectBootKeyMetadata(
    val alias: String,
    val publicKeySha256: String,
    val appSigningCertificateSha256: String,
) {
    init {
        require(alias == DirectBootKeyAuthority.KEY_ALIAS) { "unexpected key alias" }
        requireSha256(publicKeySha256, "public key digest")
        requireSha256(appSigningCertificateSha256, "app certificate digest")
    }
}

/** Pure, strict, canonical metadata codec. */
internal object DirectBootKeyMetadataCodec {
    private const val MAGIC = 0x4D444B41 // MDKA
    private const val VERSION = 1
    internal const val MAX_BYTES = 512

    fun encode(metadata: DirectBootKeyMetadata): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.utf8(metadata.alias)
            out.utf8(metadata.publicKeySha256)
            out.utf8(metadata.appSigningCertificateSha256)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): DirectBootKeyMetadata = require(bytes.size in 1..MAX_BYTES) {
        "metadata size invalid"
    }.let {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "metadata magic invalid" }
            require(input.readInt() == VERSION) { "metadata version invalid" }
            val metadata = DirectBootKeyMetadata(
                alias = input.utf8(128),
                publicKeySha256 = input.utf8(64),
                appSigningCertificateSha256 = input.utf8(64),
            )
            require(input.read() == -1) { "metadata trailing bytes" }
            require(encode(metadata).contentEquals(bytes)) { "metadata noncanonical" }
            metadata
        }
    }

    private fun DataOutputStream.utf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.utf8(max: Int): String {
        val size = readInt()
        require(size in 1..max) { "metadata field size invalid" }
        val bytes = ByteArray(size).also(::readFully)
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "metadata invalid UTF-8" }
        return value
    }
}

/** Device-protected only. There is deliberately no credential-protected fallback. */
internal fun interface DirectBootMetadataDirectorySyncer {
    /** True when synced, or when the platform explicitly does not support directory fsync. */
    fun sync(directory: File): Boolean
}

internal class DirectBootKeyMetadataStore(
    private val directory: File,
    private val directorySyncer: DirectBootMetadataDirectorySyncer = DirectBootMetadataDirectorySyncer(::syncDirectory),
) {
    constructor(
        context: Context,
        directorySyncer: DirectBootMetadataDirectorySyncer = DirectBootMetadataDirectorySyncer(::syncDirectory),
    ) : this(
        directory = checkNotNull(context.applicationContext.createDeviceProtectedStorageContext().filesDir) {
            "device-protected filesDir unavailable"
        },
        directorySyncer = directorySyncer,
    )

    init {
        require(directory.isAbsolute) { "metadata directory must be absolute" }
    }
    private val record = File(directory, METADATA_FILE)
    private val staging = File(directory, ".$METADATA_FILE.tmp")

    fun load(): DirectBootKeyMetadata? {
        if (!record.exists()) return null
        require(record.length() in 1..DirectBootKeyMetadataCodec.MAX_BYTES.toLong()) {
            "metadata size invalid"
        }
        return FileInputStream(record).use { DirectBootKeyMetadataCodec.decode(it.readBytes()) }
    }

    /** A leftover staging record is ambiguous interrupted-write truth and must fail closed. */
    fun hasStagingRecord(): Boolean = staging.exists()

    fun writeFresh(metadata: DirectBootKeyMetadata): Boolean = runCatching {
        if (record.exists() || staging.exists()) return false
        directory.mkdirs()
        require(directory.isDirectory) { "metadata directory unavailable" }
        FileOutputStream(staging).use { output ->
            output.write(DirectBootKeyMetadataCodec.encode(metadata))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(staging.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            return false
        }
        directorySyncer.sync(directory)
    }.getOrDefault(false)

    companion object { const val METADATA_FILE = "mimir_direct_boot_key_authority.v1" }
}

private fun syncDirectory(directory: File): Boolean = try {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
    true
} catch (error: ErrnoException) {
    // Android/Linux filesystems may legitimately not implement directory fsync. Any other
    // errno is a failed durability boundary and must prevent success from being reported.
    error.errno == OsConstants.EINVAL || error.errno == OsConstants.ENOTSUP
}

/** The legacy name is only a nested authority typealias; it does not create a mint seam. */
internal typealias DirectBootKeyAuthority = DirectBootKeyAuthorityTruth.Available.DirectBootKeyAuthority
internal typealias PinnedRestoreGuardianVerifier = DirectBootKeyAuthorityTruth.Available.PinnedRestoreGuardianVerifier
internal typealias PinnedTransactionJournalVerifier = DirectBootKeyAuthorityTruth.Available.PinnedTransactionJournalVerifier

private fun requireP256(key: Any) {
    val ec = key as? ECKey ?: error("key is not EC")
    val params = ec.params ?: error("EC parameters missing")
    val field = params.curve.field as? ECFieldFp
    require(
        field?.p == p256Hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF") &&
            params.curve.a == p256Hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC") &&
            params.curve.b == p256Hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B") &&
            params.generator.affineX == p256Hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296") &&
            params.generator.affineY == p256Hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5") &&
            params.order == p256Hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551") &&
            params.cofactor == 1,
    ) { "EC curve is not P-256" }
}

private fun p256Hex(value: String): BigInteger = BigInteger(value, 16)

private fun requireSha256(value: String, label: String) = require(Regex("[0-9a-f]{64}").matches(value)) {
    "$label invalid"
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun byteArrayComparator(): Comparator<ByteArray> = Comparator { left, right ->
    val limit = minOf(left.size, right.size)
    for (index in 0 until limit) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) return@Comparator comparison
    }
    left.size.compareTo(right.size)
}
