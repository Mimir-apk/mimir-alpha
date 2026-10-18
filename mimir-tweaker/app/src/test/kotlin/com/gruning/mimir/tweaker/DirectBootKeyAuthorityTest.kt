package com.gruning.mimir.tweaker

import android.content.Context
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.EllipticCurve
import java.math.BigInteger
import java.nio.file.Files
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootKeyAuthorityTest {
    @Test
    fun `metadata codec is canonical and rejects malformed reordered truncated and trailing bytes`() {
        val metadata = metadata()
        val encoded = DirectBootKeyMetadataCodec.encode(metadata)

        assertEquals(metadata, DirectBootKeyMetadataCodec.decode(encoded))
        listOf(
            ByteArray(0),
            encoded.copyOf(encoded.size - 1),
            encoded + 0,
            encoded.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() },
            reorderFirstTwoFields(encoded),
        ).forEach { candidate ->
            assertFails { DirectBootKeyMetadataCodec.decode(candidate) }
        }
    }

    @Test
    fun `metadata store uses device protected context and refuses overwrite or stale staging`() {
        val context = RuntimeEnvironment.getApplication()
        val protected = context.createDeviceProtectedStorageContext()
        val record = File(protected.filesDir, DirectBootKeyMetadataStore.METADATA_FILE)
        val staging = File(protected.filesDir, ".${DirectBootKeyMetadataStore.METADATA_FILE}.tmp")
        record.delete()
        staging.delete()
        try {
            var directorySyncs = 0
            val store = DirectBootKeyMetadataStore(context) { directory ->
                assertEquals(protected.filesDir, directory)
                directorySyncs += 1
                true
            }
            assertNull(store.load())
            assertTrue(store.writeFresh(metadata()))
            assertEquals(1, directorySyncs)
            assertEquals(metadata(), store.load())
            assertFalse(store.writeFresh(metadata()))

            assertTrue(record.delete())
            staging.writeText("partial")
            assertTrue(staging.exists())
            assertFalse(store.writeFresh(metadata()))
            assertTrue(staging.exists())
        } finally {
            record.delete()
            staging.delete()
        }
    }

    @Test
    fun `stale metadata staging inspects corrupt and prevents provisioning before keystore access`() {
        val context = RuntimeEnvironment.getApplication()
        val protected = context.createDeviceProtectedStorageContext()
        val record = File(protected.filesDir, DirectBootKeyMetadataStore.METADATA_FILE)
        val staging = File(protected.filesDir, ".${DirectBootKeyMetadataStore.METADATA_FILE}.tmp")
        record.delete()
        staging.delete()
        try {
            staging.writeText("interrupted metadata write")
            var keyStoreFactoryCalls = 0
            val authority = reflectedDirectBootKeyAuthority(
                metadataStore = DirectBootKeyMetadataStore(context),
                certificateDigestSource = AppSigningCertificateDigestSource { "c".repeat(64) },
                keyStoreFactory = {
                    keyStoreFactoryCalls += 1
                    error("staging truth must fail before keystore access")
                },
                keyPairGeneratorFactory = { error("staging truth must not generate a key") },
            )

            assertTrue(authority.inspect() is DirectBootKeyAuthorityTruth.Corrupt)
            assertTrue(authority.provisionFresh() is DirectBootKeyAuthorityTruth.Corrupt)
            assertEquals(0, keyStoreFactoryCalls)
        } finally {
            record.delete()
            staging.delete()
        }
    }

    @Test
    fun `app signing certificate set digest is sorted and length prefixed`() {
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)

        assertEquals(
            "13bef33114afeba5cb85e83f14c024e5523c9835db10712f5c58e18aa0efd6f6",
            canonicalAppSigningCertificateSetDigest(listOf(first, second)),
        )
        assertEquals(
            canonicalAppSigningCertificateSetDigest(listOf(first, second)),
            canonicalAppSigningCertificateSetDigest(listOf(second, first)),
        )
        assertFalse(
            canonicalAppSigningCertificateSetDigest(listOf(first, second)) ==
                canonicalAppSigningCertificateSetDigest(listOf(byteArrayOf(1, 2))),
        )
    }

    @Test
    fun `available authority is non data capability and distinct direct boot inspections do not share authority`() {
        val first = GuardianTestKeyAuthority.create("a".repeat(64)).available
        val second = GuardianTestKeyAuthority.create("a".repeat(64)).available
        assertFalse(first === second)
        assertFalse(PinnedRestoreGuardianVerifier::class.java.methods.any { it.name == "copy" })
        assertFalse(PinnedTransactionJournalVerifier::class.java.methods.any { it.name == "copy" })
    }

    @Test
    fun `available capability and pinned operations have exact reflection allowlists`() {
        val available = DirectBootKeyAuthorityTruth.Available::class.java
        val internalSuffix =
            "${'$'}app_${BuildConfig.FLAVOR}${BuildConfig.BUILD_TYPE.replaceFirstChar(Char::uppercase)}"
        // Kotlin emits a synthetic DefaultConstructorMarker bridge for a private primary
        // constructor. It is not an application construction surface; the one real
        // constructor must remain private.
        val realConstructors = available.declaredConstructors.filterNot { it.isSynthetic }
        assertEquals(1, realConstructors.size)
        assertTrue(java.lang.reflect.Modifier.isPrivate(realConstructors.single().modifiers))
        assertEquals(
            setOf("signer", "publicKey", "keyIdentitySha256", "appSigningCertificateSha256", "capability"),
            available.declaredFields.map { it.name }.toSet(),
        )
        assertTrue(available.declaredFields.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        assertEquals(
            setOf(
                "envelopeVerifier$internalSuffix", "journalVerifier$internalSuffix",
                "verifierFor", "journalFor", "verifiesMgde",
                "signJournal$internalSuffix", "signFpsJournal$internalSuffix",
                "verifiesFpsJournal$internalSuffix", "signEnvelope$internalSuffix",
                "signHeartbeat$internalSuffix",
                "signSessionPin$internalSuffix",
            ),
            available.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet(),
        )

        val capability = available.declaredClasses.single { it.simpleName == "Capability" }
        assertTrue(java.lang.reflect.Modifier.isFinal(capability.modifiers))
        assertEquals(
            setOf("keyIdentitySha256", "appSigningCertificateSha256"),
            capability.declaredFields.map { it.name }.toSet(),
        )
        assertTrue(capability.declaredFields.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        assertEquals(
            setOf("requireEnvelopeBinding$internalSuffix", "requireJournalBinding$internalSuffix"),
            capability.declaredMethods.map { it.name }.toSet(),
        )
        assertEquals(1, capability.declaredConstructors.size)
        assertTrue(java.lang.reflect.Modifier.isPrivate(capability.declaredConstructors.single().modifiers))

        val envelopeVerifier = PinnedRestoreGuardianVerifier::class.java
        assertEquals(setOf("publicKey", "pinnedTweakerCertificateSha256"), envelopeVerifier.declaredFields.map { it.name }.toSet())
        assertTrue(envelopeVerifier.declaredFields.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        assertEquals(
            setOf(
                "trustedFor", "verifier", "writeArmRecordBinding$internalSuffix",
                "matchesArmRecordBinding$internalSuffix", "matchesSessionSec1$internalSuffix",
            ),
            envelopeVerifier.declaredMethods.map { it.name }.toSet(),
        )
        assertEquals(1, envelopeVerifier.declaredConstructors.size)
        assertEquals(
            DirectBootKeyAuthorityTruth.Available.Capability::class.java,
            envelopeVerifier.declaredConstructors.single().parameterTypes.last(),
        )

        val journalVerifier = PinnedTransactionJournalVerifier::class.java
        assertEquals(
            setOf("keyIdentity", "publicKey", "pinnedTweakerCertificateSha256"),
            journalVerifier.declaredFields.map { it.name }.toSet(),
        )
        assertTrue(journalVerifier.declaredFields.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        assertEquals(
            setOf(
                "verifier", "matchesKeyIdentity$internalSuffix",
                "verifier${'$'}lambda${'$'}0",
            ),
            journalVerifier.declaredMethods.map { it.name }.toSet(),
        )
        assertEquals(1, journalVerifier.declaredConstructors.size)
        assertEquals(
            DirectBootKeyAuthorityTruth.Available.Capability::class.java,
            journalVerifier.declaredConstructors.single().parameterTypes.last(),
        )
    }

    @Test
    fun `compiler negative fixture and gate retain every forbidden construction attempt`() {
        val project = File(checkNotNull(System.getProperty("user.dir")))
        val fixture = File(project, "src/test/fixtures/B3ForgeAttempts.kt.fixture")
        val script = File(project.parentFile, "tools/mimir-b3-compiler-negative.sh")
        assertTrue("B3 forge fixture missing", fixture.isFile)
        assertTrue("B3 compiler gate missing", script.isFile)
        val fixtureText = fixture.readText()
        val scriptText = script.readText()
        val markers = listOf(
            "AVAILABLE_CTOR", "AVAILABLE_COPY_COMPONENT", "AVAILABLE_SUBCLASS",
            "CAPABILITY_CTOR_AND_SOURCE", "INJECTABLE_AUTHORITY_CTOR",
            "PRIVATE_MINTER", "OLD_ENVELOPE_FROM",
            "OLD_JOURNAL_FROM", "RAW_AVAILABLE_FIELDS", "REMOVED_SIGN_BOUND",
            "FABRICATED_ENVELOPE_VERIFIER", "FABRICATED_JOURNAL_VERIFIER",
            "SEALED_TRUTH_SUBCLASS", "FORGED_MGDE_RECORD", "FORGED_MGDE_SOURCE",
            "FORGED_MGDE_SOURCE_SUBCLASS",
        )
        markers.forEach { marker ->
            assertTrue("fixture marker missing: $marker", fixtureText.contains("B3_NEGATIVE:$marker"))
            assertTrue("gate marker missing: $marker", scriptText.contains(marker))
        }
    }

    @Test
    fun `MGDE authority seam accepts no raw key verifier capability boolean or replay ledger`() {
        val method = DirectBootKeyAuthority::class.java.declaredMethods.single {
            it.name.startsWith("admitMgde")
        }
        val names = method.parameterTypes.map { it.name }
        assertTrue(names.any { it.endsWith(".mgde.MgdeObservedSource") })
        assertTrue(names.any { it.endsWith(".mgde.MgdeExpectedIdentity") })
        assertTrue(names.any { it == "[B" })
        assertTrue(names.any { it.endsWith(".HardwareCapabilities") })
        assertFalse(names.any { it.contains("PublicKey") })
        assertFalse(names.any { it.contains("Verifier") })
        assertFalse(names.any { it.contains("MgdeReplayLedger") })
        assertFalse(names.any { it == "boolean" || it == "java.lang.Boolean" })
    }

    @Test
    fun `minted capability rejects verifier and journal material from another key or certificate`() {
        val certificate = digest("certificate")
        val firstPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val first = GuardianTestKeyAuthority.create(certificate, firstPair).available
        val secondPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val capability = DirectBootKeyAuthorityTruth.Available::class.java
            .getDeclaredField("capability")
            .also { it.isAccessible = true }
            .get(first) as DirectBootKeyAuthorityTruth.Available.Capability
        assertFails {
            PinnedRestoreGuardianVerifier(secondPair.public, certificate, capability)
        }
        assertFails {
            PinnedRestoreGuardianVerifier(
                firstPair.public,
                digest("other-certificate"),
                capability,
            )
        }
        assertFails {
            PinnedTransactionJournalVerifier(
                digest(secondPair.public.encoded),
                secondPair.public,
                certificate,
                capability,
            )
        }
    }

    @Test
    fun `authority rejects wrong provider exportable key digest mismatch and non P256 key`() {
        assertTrue(GuardianTestKeyAuthority.inspect(providerName = "WrongProvider") is DirectBootKeyAuthorityTruth.Corrupt)
        assertTrue(GuardianTestKeyAuthority.inspect(exportablePrivateKey = true) is DirectBootKeyAuthorityTruth.Corrupt)
        assertTrue(GuardianTestKeyAuthority.inspect(metadataPublicKeyDigest = "0".repeat(64)) is DirectBootKeyAuthorityTruth.Corrupt)
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { GuardianTestKeyAuthority.inspect(pair = rsa, certificatePublicKey = rsa.public) }
            .getOrElse { DirectBootKeyAuthorityTruth.Corrupt("non-p256 rejected") } is DirectBootKeyAuthorityTruth.Corrupt)
    }

    @Test
    fun `authority state matrix rejects certificate metadata and keystore ambiguity before minting`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val certificate = digest("certificate")

        withAuthorityDirectory("authority-fresh-") { directory ->
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore()).inspect() is DirectBootKeyAuthorityTruth.Fresh)
        }
        withAuthorityDirectory("authority-key-only-") { directory ->
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(pair)).inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
        withAuthorityDirectory("authority-metadata-only-") { directory ->
            writeMetadata(directory, pair, certificate)
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore()).inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
        withAuthorityDirectory("authority-cert-change-") { directory ->
            writeMetadata(directory, pair, certificate)
            // A changed app signing certificate is its own disposition, NOT Corrupt -- see
            // `certificate mismatch is CertificateChanged, distinctly, never generic Corrupt` below
            // for the dedicated pin. Asserted here too so this matrix stays a complete map of every
            // state `inspect()` can return.
            assertTrue(
                authority(directory, { digest("changed") }, guardianFakeAndroidKeyStore(pair)).inspect()
                    is DirectBootKeyAuthorityTruth.CertificateChanged,
            )
        }
        withAuthorityDirectory("authority-cert-throw-") { directory ->
            writeMetadata(directory, pair, certificate)
            assertTrue(authority(directory, { error("certificate unavailable") }, guardianFakeAndroidKeyStore(pair)).inspect() is DirectBootKeyAuthorityTruth.Unavailable)
        }
        withAuthorityDirectory("authority-cert-malformed-") { directory ->
            writeMetadata(directory, pair, certificate)
            assertTrue(authority(directory, { "not-a-digest" }, guardianFakeAndroidKeyStore(pair)).inspect() is DirectBootKeyAuthorityTruth.Unavailable)
        }
        withAuthorityDirectory("authority-metadata-unreadable-") { directory ->
            File(directory, DirectBootKeyMetadataStore.METADATA_FILE).writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(pair)).inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
        withAuthorityDirectory("authority-metadata-alias-") { directory ->
            val encoded = DirectBootKeyMetadataCodec.encode(metadataFor(pair, certificate))
            val alias = DirectBootKeyAuthority.KEY_ALIAS.toByteArray()
            val offset = encoded.indexOf(alias)
            assertTrue(offset >= 0)
            encoded[offset] = (encoded[offset].toInt() xor 1).toByte()
            File(directory, DirectBootKeyMetadataStore.METADATA_FILE).writeBytes(encoded)
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(pair)).inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
        withAuthorityDirectory("authority-keystore-factory-") { directory ->
            writeMetadata(directory, pair, certificate)
            val authority = reflectedDirectBootKeyAuthority(
                DirectBootKeyMetadataStore(directory),
                AppSigningCertificateDigestSource { certificate },
                keyStoreFactory = { error("keystore unavailable") },
                keyPairGeneratorFactory = { error("must not generate") },
            )
            assertTrue(authority.inspect() is DirectBootKeyAuthorityTruth.Unavailable)
        }
        withAuthorityDirectory("authority-keystore-load-") { directory ->
            writeMetadata(directory, pair, certificate)
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(pair, loadFailure = true)).inspect() is DirectBootKeyAuthorityTruth.Unavailable)
        }
        withAuthorityDirectory("authority-keystore-contains-") { directory ->
            writeMetadata(directory, pair, certificate)
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(pair, containsFailure = true)).inspect() is DirectBootKeyAuthorityTruth.Unavailable)
        }
        withAuthorityDirectory("authority-metadata-oversize-") { directory ->
            File(directory, DirectBootKeyMetadataStore.METADATA_FILE)
                .writeBytes(ByteArray(DirectBootKeyMetadataCodec.MAX_BYTES + 1))
            var keyStoreCalls = 0
            val authority = reflectedDirectBootKeyAuthority(
                DirectBootKeyMetadataStore(directory),
                AppSigningCertificateDigestSource { certificate },
                keyStoreFactory = {
                    keyStoreCalls += 1
                    guardianFakeAndroidKeyStore(pair)
                },
                keyPairGeneratorFactory = { error("must not generate") },
            )
            assertTrue(authority.inspect() is DirectBootKeyAuthorityTruth.Corrupt)
            assertEquals(0, keyStoreCalls)
        }
    }

    @Test
    fun `certificate mismatch is CertificateChanged, distinctly, never generic Corrupt`() {
        // App re-signing can legitimately change the signing certificate; a changed
        // certificate alone is not evidence of tampering. Before this type
        // existed it returned Corrupt("app signing certificate changed") -- indistinguishable in
        // every downstream surface (journal, arm store, the `truth=` boot log) from a genuine
        // metadata/key inconsistency. Pinned so a future edit cannot fold the two back together.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val original = digest("original-certificate")
        withAuthorityDirectory("authority-cert-changed-pin-") { directory ->
            writeMetadata(directory, pair, original)
            val resigned = authority(directory, { digest("resigned-certificate") }, guardianFakeAndroidKeyStore(pair))
                .inspect()
            assertTrue(
                "a certificate mismatch must be CertificateChanged, never Corrupt",
                resigned is DirectBootKeyAuthorityTruth.CertificateChanged,
            )
            assertFalse(
                "CertificateChanged must not also satisfy Corrupt -- the two are siblings, not " +
                    "one subtyping the other",
                resigned is DirectBootKeyAuthorityTruth.Corrupt,
            )
            assertEquals(
                "app signing certificate changed",
                (resigned as DirectBootKeyAuthorityTruth.CertificateChanged).reason,
            )
        }
        // CONTROL: an unrelated genuine corruption (metadata present, no keystore alias) must
        // still be Corrupt, not CertificateChanged -- the fix must not have widened CertificateChanged
        // into the general "something in the pair disagrees" bucket it was meant to narrow.
        withAuthorityDirectory("authority-cert-changed-control-") { directory ->
            writeMetadata(directory, pair, original)
            val stillCorrupt = authority(directory, { original }, guardianFakeAndroidKeyStore()).inspect()
            assertTrue(stillCorrupt is DirectBootKeyAuthorityTruth.Corrupt)
        }
    }

    @Test
    fun `authority key matrix rejects non private wrong algorithm secp256k1 and mismatched public key`() {
        val certificate = digest("certificate")
        val p256 = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val otherP256 = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        withAuthorityDirectory("authority-non-private-") { directory ->
            writeMetadata(directory, p256, certificate)
            assertTrue(
                authority(
                    directory,
                    { certificate },
                    guardianFakeAndroidKeyStore(p256, entryIsPrivate = false),
                ).inspect() is DirectBootKeyAuthorityTruth.Corrupt,
            )
        }
        withAuthorityDirectory("authority-rsa-") { directory ->
            val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            writeMetadata(directory, rsa, certificate)
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(rsa)).inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
        withAuthorityDirectory("authority-secp256k1-") { directory ->
            val secp256k1 = pairWithParams(p256, secp256k1Parameters())
            writeMetadata(directory, secp256k1, certificate)
            assertTrue(authority(directory, { certificate }, guardianFakeAndroidKeyStore(secp256k1)).inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
        withAuthorityDirectory("authority-public-mismatch-") { directory ->
            writeMetadata(directory, KeyPair(otherP256.public, p256.private), certificate)
            assertTrue(
                authority(
                    directory,
                    { certificate },
                    guardianFakeAndroidKeyStore(p256, certificatePublicKey = otherP256.public),
                ).inspect() is DirectBootKeyAuthorityTruth.Corrupt,
            )
        }
    }

    @Test
    fun `provision matrix never regenerates nonfresh and fails closed on generator metadata race and sync ambiguity`() {
        val certificate = digest("certificate")
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        withAuthorityDirectory("authority-provision-nonfresh-") { directory ->
            writeMetadata(directory, pair, certificate)
            var generatorCalls = 0
            val authority = reflectedDirectBootKeyAuthority(
                DirectBootKeyMetadataStore(directory),
                AppSigningCertificateDigestSource { certificate },
                keyStoreFactory = { guardianFakeAndroidKeyStore(pair) },
                keyPairGeneratorFactory = {
                    generatorCalls += 1
                    FixedKeyPairGenerator { pair }
                },
            )
            assertTrue(authority.provisionFresh() is DirectBootKeyAuthorityTruth.Available)
            assertEquals(0, generatorCalls)
        }
        withAuthorityDirectory("authority-generator-failure-") { directory ->
            val authority = reflectedDirectBootKeyAuthority(
                DirectBootKeyMetadataStore(directory),
                AppSigningCertificateDigestSource { certificate },
                keyStoreFactory = { guardianFakeAndroidKeyStore() },
                keyPairGeneratorFactory = { error("generator unavailable") },
            )
            assertTrue(authority.provisionFresh() is DirectBootKeyAuthorityTruth.Unavailable)
        }
        withAuthorityDirectory("authority-metadata-race-") { directory ->
            var keyStoreCalls = 0
            var generatorCalls = 0
            val authority = reflectedDirectBootKeyAuthority(
                DirectBootKeyMetadataStore(directory),
                AppSigningCertificateDigestSource { certificate },
                keyStoreFactory = {
                    keyStoreCalls += 1
                    if (keyStoreCalls == 1) writeMetadata(directory, pair, certificate)
                    guardianFakeAndroidKeyStore()
                },
                keyPairGeneratorFactory = {
                    generatorCalls += 1
                    FixedKeyPairGenerator { pair }
                },
            )
            assertTrue(authority.provisionFresh() is DirectBootKeyAuthorityTruth.Corrupt)
            assertEquals(0, generatorCalls)
        }
        withAuthorityDirectory("authority-sync-ambiguity-") { directory ->
            val metadataStore = DirectBootKeyMetadataStore(directory, DirectBootMetadataDirectorySyncer { false })
            val authority = reflectedDirectBootKeyAuthority(
                metadataStore,
                AppSigningCertificateDigestSource { certificate },
                keyStoreFactory = { guardianFakeAndroidKeyStore() },
                keyPairGeneratorFactory = { FixedKeyPairGenerator { pair } },
            )
            assertTrue(authority.provisionFresh() is DirectBootKeyAuthorityTruth.Unavailable)
            assertTrue(File(directory, DirectBootKeyMetadataStore.METADATA_FILE).exists())
            assertFalse(File(directory, ".${DirectBootKeyMetadataStore.METADATA_FILE}.tmp").exists())
            assertTrue(authority.inspect() is DirectBootKeyAuthorityTruth.Corrupt)
        }
    }

    private fun metadata() = DirectBootKeyMetadata(
        alias = DirectBootKeyAuthority.KEY_ALIAS,
        publicKeySha256 = "a".repeat(64),
        appSigningCertificateSha256 = "b".repeat(64),
    )

    private fun authority(
        directory: File,
        certificate: () -> String,
        keyStore: KeyStore,
    ) = reflectedDirectBootKeyAuthority(
        metadataStore = DirectBootKeyMetadataStore(directory),
        certificateDigestSource = AppSigningCertificateDigestSource { certificate() },
        keyStoreFactory = { keyStore },
        keyPairGeneratorFactory = { error("inspection must not generate") },
    )

    private fun metadataFor(pair: KeyPair, certificate: String) = DirectBootKeyMetadata(
        alias = DirectBootKeyAuthority.KEY_ALIAS,
        publicKeySha256 = digest(pair.public.encoded),
        appSigningCertificateSha256 = certificate,
    )

    private fun writeMetadata(directory: File, pair: KeyPair, certificate: String) {
        check(
            DirectBootKeyMetadataStore(
                directory,
                DirectBootMetadataDirectorySyncer { true },
            ).writeFresh(metadataFor(pair, certificate)),
        )
    }

    private fun digest(value: String): String = digest(value.toByteArray())
    private fun digest(value: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private fun withAuthorityDirectory(prefix: String, block: (File) -> Unit) {
        val directory = Files.createTempDirectory(prefix).toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun pairWithParams(pair: KeyPair, params: ECParameterSpec): KeyPair {
        val privateKey = pair.private as ECPrivateKey
        val publicKey = pair.public as ECPublicKey
        return KeyPair(
            object : ECPublicKey {
                override fun getAlgorithm(): String = "EC"
                override fun getFormat(): String = publicKey.format
                override fun getEncoded(): ByteArray = publicKey.encoded
                override fun getW(): ECPoint = publicKey.w
                override fun getParams(): ECParameterSpec = params
            },
            object : ECPrivateKey {
                override fun getAlgorithm(): String = "EC"
                override fun getFormat(): String = privateKey.format
                override fun getEncoded(): ByteArray = privateKey.encoded
                override fun getS(): BigInteger = privateKey.s
                override fun getParams(): ECParameterSpec = params
            },
        )
    }

    private fun secp256k1Parameters(): ECParameterSpec = ECParameterSpec(
        EllipticCurve(
            ECFieldFp(BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)),
            BigInteger.ZERO,
            BigInteger.valueOf(7),
        ),
        ECPoint(
            BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
        ),
        BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16),
        1,
    )

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        for (index in 0..size - needle.size) {
            if (copyOfRange(index, index + needle.size).contentEquals(needle)) return index
        }
        return -1
    }

    private fun reorderFirstTwoFields(bytes: ByteArray): ByteArray {
        // Header is two ints. The fixed alias is the first field; swapping it with the digest
        // must not be accepted as a plausible alternate encoding.
        val start = 8
        val firstSize = readInt(bytes, start)
        val firstEnd = start + 4 + firstSize
        val secondSize = readInt(bytes, firstEnd)
        val secondEnd = firstEnd + 4 + secondSize
        return bytes.copyOfRange(0, start) +
            bytes.copyOfRange(firstEnd, secondEnd) +
            bytes.copyOfRange(start, firstEnd) +
            bytes.copyOfRange(secondEnd, bytes.size)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private class FixedKeyPairGenerator(
        private val produce: () -> KeyPair,
    ) : KeyPairGenerator("EC") {
        override fun initialize(keysize: Int, random: SecureRandom?) = Unit
        override fun initialize(params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
        override fun generateKeyPair(): KeyPair = produce()
    }
}
