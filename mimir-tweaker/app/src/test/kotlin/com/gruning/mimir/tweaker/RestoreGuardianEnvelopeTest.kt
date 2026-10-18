package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardianEnvelopeTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val otherKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signer = EcdsaP256Signer(keyPair.private)
    private val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
    private val appSessionKey = GuardianSessionPublicKey.fromPublicKey(keyPair.public)
    private val otherSessionKey = GuardianSessionPublicKey.fromPublicKey(otherKeyPair.public)
    private val sessionSigner = EcdsaP256GuardianSessionSigner(sessionPair.private)
    private val trustedAvailable = GuardianTestKeyAuthority.create(sha("certificate"), keyPair).available
    private val trusted = trustedAvailable.envelopeVerifier()

    @Test
    fun `canonical envelope is byte stable and every identity field is covered`() {
        val original = envelope()
        assertTrue(original.canonicalBytes().contentEquals(envelope().canonicalBytes()))
        val bytes = original.canonicalBytes()
        assertRejected { original.copy(schemaVersion = 1) }
        val tampered = listOf(
            original.copy(helperBuildIdentity = "helper.2"),
            original.copy(tweakerCertificateSha256 = sha("other-certificate")),
            original.copy(hardwareFingerprint = "hardware.2"),
            original.copy(deviceProfile = "profile.2"),
            original.copy(bootIdentity = "boot.2"),
            original.copy(generation = 8),
            original.copy(requestId = "request.2"),
            original.copy(transactionId = "transaction.2"),
            original.copy(taskNonce = "b".repeat(32)),
            original.copy(topologySha256 = sha("topology.2")),
            original.copy(snapshotSha256 = sha("snapshot.2")),
            original.copy(planSha256 = sha("plan.2")),
            original.copy(hardDeadlineElapsedRealtimeMs = 10_001),
            original.copy(restoreEntries = listOf(sysfs(4), fanPut(2))),
        )
        tampered.forEach { assertFalse("field tamper escaped canonical bytes", bytes.contentEquals(it.canonicalBytes())) }
        val signed = RestoreGuardianCrypto.signEnvelope(original, signer)
        tampered.forEach { assertFalse("field tamper retained a valid signature", RestoreGuardianCrypto.verifyEnvelope(signed.copy(envelope = it), trusted)) }
        assertNotEquals(original.digestSha256(), original.copy(planSha256 = sha("plan.2")).digestSha256())
    }

    @Test
    fun `signed envelope fails closed for wrong key or app provenance`() {
        val signed = RestoreGuardianCrypto.signEnvelope(envelope(), signer)
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(signed, trusted))
        assertFalse(RestoreGuardianCrypto.verifyEnvelope(signed.copy(signature = signed.signature.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }), trusted))
        assertFalse(
            RestoreGuardianCrypto.verifyEnvelope(
                signed,
                GuardianTestKeyAuthority.create(sha("certificate"), otherKeyPair).available.envelopeVerifier(),
            ),
        )
        assertFalse(
            RestoreGuardianCrypto.verifyEnvelope(
                signed,
                GuardianTestKeyAuthority.create(sha("not-the-app"), keyPair).available.envelopeVerifier(),
            ),
        )
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(384) }.generateKeyPair()
        assertRejected { EcdsaP256Signer(p384.private) }
        assertRejected { EcdsaP256Verifier(p384.public) }
    }

    @Test
    fun `canonical entry order and typed stock values reject malformed or unknown intermediate variants`() {
        val entry = sysfs(ordinal = 3)
        assertRejected { envelope(entries = emptyList()) }
        assertRejected { envelope(entries = listOf(entry, sysfs(ordinal = 4, id = "cpu:2:scaling_max_freq"))) }
        assertRejected { envelope(entries = listOf(entry, sysfs(ordinal = 3, id = "cpu:2:scaling_max_freq"))) }
        assertRejected { RestoreGuardianSysfsEntry(1, "cpu:1:scaling_max_freq", "/tmp/no", RestoreGuardianStockState.PresentSysfs(1, "644", 0, 0), listOf(2)) }
        assertRejected { RestoreGuardianSysfsEntry(1, "cpu:1:scaling_max_freq", "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq", RestoreGuardianStockState.PresentSysfs(1, "bad", 0, 0), listOf(2)) }
        assertRejected { RestoreGuardianFanPutEntry(1, expectedStock = RestoreGuardianStockState.PresentFanMode(99), rendererAppliedIntermediates = listOf(1)) }
        assertRejected { RestoreGuardianFanDeleteEntry(1, rendererAppliedIntermediates = listOf(1)) }
        assertRejected { RestoreGuardianSysfsEntry(1, "cpu:1:scaling_max_freq", "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq", RestoreGuardianStockState.PresentSysfs(1, "644", 0, 0), emptyList()) }
        assertRejected { RestoreGuardianSysfsEntry(1, "cpu:1:scaling_max_freq", "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq", RestoreGuardianStockState.PresentSysfs(1, "644", -1, 0), listOf(2)) }
        assertRejected { RestoreGuardianSysfsEntry(1, "cpu:1:scaling_max_freq", "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq", RestoreGuardianStockState.PresentSysfs(1, "644", 0, -1), listOf(2)) }

        val original = envelope()
        val signed = RestoreGuardianCrypto.signEnvelope(original, signer)
        val sysfs = original.restoreEntries.first() as RestoreGuardianSysfsEntry
        listOf(
            sysfs.copy(expectedStock = sysfs.expectedStock.copy(value = sysfs.expectedStock.value + 1)),
            sysfs.copy(expectedStock = sysfs.expectedStock.copy(mode = "600")),
            sysfs.copy(expectedStock = sysfs.expectedStock.copy(uid = 1)),
            sysfs.copy(expectedStock = sysfs.expectedStock.copy(gid = 1)),
            sysfs.copy(rendererAppliedIntermediates = listOf(1_999_999)),
        ).forEach { altered ->
            assertFalse(RestoreGuardianCrypto.verifyEnvelope(signed.copy(envelope = original.copy(restoreEntries = listOf(altered, original.restoreEntries[1]))), trusted))
        }
    }

    @Test
    fun `restore entry construction boundary exactly mirrors durable decoder`() {
        val maxEntries = (32 downTo 1).map { ordinal ->
            RestoreGuardianSysfsEntry(
                applyOrdinal = ordinal,
                logicalId = "cpu:$ordinal:scaling_max_freq",
                canonicalSysfsPath = "/sys/devices/system/cpu/cpufreq/policy$ordinal/scaling_max_freq",
                expectedStock = RestoreGuardianStockState.PresentSysfs(1_200_000, "644", 0, 0),
                rendererAppliedIntermediates = listOf(2_000_000),
            )
        }
        assertEquals(32, envelope(maxEntries).restoreEntries.size)
        assertRejected { envelope(maxEntries + RestoreGuardianFanPutEntry(0, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = listOf(2))) }
    }

    @Test
    fun `ready receipt binds complete envelope identity`() {
        val value = envelope()
        val receipt = receipt(value)
        val signed = RestoreGuardianCrypto.signReadyReceipt(receipt, sessionSigner)
        assertTrue(RestoreGuardianCrypto.verifyReadyReceipt(signed, value))
        assertTrue(RestoreGuardianCrypto.receiptMatches(value, receipt))
        listOf(
            receipt.copy(helperBuildIdentity = "helper.2"), receipt.copy(bootIdentity = "boot.2"),
            receipt.copy(generation = 8), receipt.copy(requestId = "request.2"),
            receipt.copy(transactionId = "transaction.2"), receipt.copy(taskNonce = "b".repeat(32)),
            receipt.copy(envelopeSha256 = sha("wrong")),
        ).forEach { assertFalse(RestoreGuardianCrypto.receiptMatches(value, it)) }
        assertFalse(RestoreGuardianCrypto.receiptMatches(value, receipt.copy(issuedElapsedRealtimeMs = value.hardDeadlineElapsedRealtimeMs + 1)))
    }

    @Test
    fun `terminal receipts are canonical signed chained and defensively copied`() {
        val value = envelope()
        val stock = stock(value)
        val signedStock = RestoreGuardianCrypto.signStockConfirmedReceipt(stock, sessionSigner)
        val exited = exited(value, stock.digestSha256())
        val signedExited = RestoreGuardianCrypto.signExitedReceipt(exited, sessionSigner)
        assertTrue(stock.canonicalBytes().contentEquals(stock(value).canonicalBytes()))
        assertTrue(exited.canonicalBytes().contentEquals(exited(value, stock.digestSha256()).canonicalBytes()))
        assertTrue(RestoreGuardianCrypto.verifyStockConfirmedReceipt(signedStock, sessionKey, value))
        assertTrue(RestoreGuardianCrypto.verifyExitedReceipt(signedExited, sessionKey, value))
        val leaked = signedStock.signature; leaked[0] = (leaked[0].toInt() xor 1).toByte()
        assertTrue(RestoreGuardianCrypto.verifyStockConfirmedReceipt(signedStock, sessionKey, value))
        val exitedLeak = signedExited.signature; exitedLeak[0] = (exitedLeak[0].toInt() xor 1).toByte()
        assertTrue(RestoreGuardianCrypto.verifyExitedReceipt(signedExited, sessionKey, value))
        assertFalse(RestoreGuardianCrypto.verifyStockConfirmedReceipt(SignedRestoreGuardianStockConfirmedReceipt(stock, signedStock.signature.also { it[0] = (it[0].toInt() xor 1).toByte() }), sessionKey, value))
        assertFalse(RestoreGuardianCrypto.verifyExitedReceipt(SignedRestoreGuardianExitedReceipt(exited, signedExited.signature.also { it[0] = (it[0].toInt() xor 1).toByte() }), sessionKey, value))
        assertFalse(RestoreGuardianCrypto.verifyStockConfirmedReceipt(signedStock, otherSessionKey, value))
        assertFalse(RestoreGuardianCrypto.verifyStockConfirmedReceipt(signedStock, appSessionKey, value))
        assertFalse(exited.stockConfirmedReceiptSha256 == stock(value.copy(requestId = "request.2")).digestSha256())
        assertFalse(exited.canonicalBytes().contentEquals(exited.copy(stockConfirmedReceiptSha256 = sha("other")).canonicalBytes()))
    }

    @Test
    fun `all signed projections pin caller signature arrays and reject oversized signatures`() {
        val value = envelope()
        val ready = RestoreGuardianCrypto.signReadyReceipt(receipt(value), sessionSigner)
        val heartbeat = RestoreGuardianCrypto.signHeartbeat(heartbeat(value, 1, 100), signer)
        val envelope = RestoreGuardianCrypto.signEnvelope(value, signer)
        listOf(
            { SignedRestoreGuardianEnvelope(value, ByteArray(513) { 1 }) },
            { SignedRestoreGuardianHeartbeat(heartbeat.heartbeat, ByteArray(513) { 1 }) },
            { SignedRestoreGuardianReadyReceipt(ready.receipt, ByteArray(513) { 1 }) },
            { SignedRestoreGuardianSessionPin(RestoreGuardianSessionPin(envelopeSha256 = value.digestSha256(), sessionKeySha256 = sessionKey.sha256(), readyReceiptSha256 = sha("ready"), readyIssuedElapsedRealtimeMs = 1, pinnedElapsedRealtimeMs = 1), ByteArray(513) { 1 }) },
        ).forEach { assertRejected { it() } }

        val envelopeRaw = envelope.signature
        val heartbeatRaw = heartbeat.signature
        val readyRaw = ready.signature
        val copiedEnvelope = SignedRestoreGuardianEnvelope(value, envelopeRaw)
        val copiedHeartbeat = SignedRestoreGuardianHeartbeat(heartbeat.heartbeat, heartbeatRaw)
        val copiedReady = SignedRestoreGuardianReadyReceipt(ready.receipt, readyRaw)
        envelopeRaw[0] = (envelopeRaw[0].toInt() xor 1).toByte()
        heartbeatRaw[0] = (heartbeatRaw[0].toInt() xor 1).toByte()
        readyRaw[0] = (readyRaw[0].toInt() xor 1).toByte()
        copiedEnvelope.signature[0] = (copiedEnvelope.signature[0].toInt() xor 1).toByte()
        copiedHeartbeat.signature[0] = (copiedHeartbeat.signature[0].toInt() xor 1).toByte()
        copiedReady.signature[0] = (copiedReady.signature[0].toInt() xor 1).toByte()
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(envelope, trusted))
        assertTrue(RestoreGuardianCrypto.verifyHeartbeat(heartbeat, trusted, value))
        assertTrue(RestoreGuardianCrypto.verifyReadyReceipt(ready, value))
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(copiedEnvelope, trusted))
        assertTrue(RestoreGuardianCrypto.verifyHeartbeat(copiedHeartbeat, trusted, value))
        assertTrue(RestoreGuardianCrypto.verifyReadyReceipt(copiedReady, value))
    }

    @Test
    fun `signed envelope pins deep entry lists from caller and returned projections`() {
        val intermediates = mutableListOf(2_000_000)
        val entries = mutableListOf<RestoreGuardianRestoreEntry>(
            sysfs(3).copy(rendererAppliedIntermediates = intermediates),
            fanPut(2),
        )
        val source = envelope(entries)
        val signed = RestoreGuardianCrypto.signEnvelope(source, signer)
        val canonical = signed.envelope.canonicalBytes()

        entries.clear()
        intermediates.clear()
        val exposed = signed.envelope
        val exposedEntries = exposed.restoreEntries as MutableList<RestoreGuardianRestoreEntry>
        val exposedIntermediates = (exposedEntries.first() as RestoreGuardianSysfsEntry).rendererAppliedIntermediates as MutableList<Int>
        exposedEntries.clear()
        runCatching { exposedIntermediates.clear() }

        assertTrue(canonical.contentEquals(signed.envelope.canonicalBytes()))
        assertEquals(2, signed.envelope.restoreEntries.size)
        assertEquals(listOf(2_000_000), (signed.envelope.restoreEntries.first() as RestoreGuardianSysfsEntry).rendererAppliedIntermediates)
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(signed, trusted))
    }

    @Test
    fun `terminal crypto itself requires exact envelope identity and session digest`() {
        val value = envelope()
        val stock = stock(value)
        val exited = exited(value, stock.digestSha256())
        assertTrue(RestoreGuardianCrypto.verifyStockConfirmedReceipt(RestoreGuardianCrypto.signStockConfirmedReceipt(stock, sessionSigner), sessionKey, value))
        assertTrue(RestoreGuardianCrypto.verifyExitedReceipt(RestoreGuardianCrypto.signExitedReceipt(exited, sessionSigner), sessionKey, value))
        val staleStock = RestoreGuardianCrypto.signStockConfirmedReceipt(stock.copy(requestId = "request.2"), sessionSigner)
        val staleExit = RestoreGuardianCrypto.signExitedReceipt(exited.copy(taskNonce = "b".repeat(32)), sessionSigner)
        assertFalse(RestoreGuardianCrypto.verifyStockConfirmedReceipt(staleStock, sessionKey, value))
        assertFalse(RestoreGuardianCrypto.verifyExitedReceipt(staleExit, sessionKey, value))
    }

    @Test
    fun `cross boot terminal crypto refuses full-Stock digest for a zero persistent axis plan`() {
        // RENAMED AND CORRECTED, 2026-09-05, while fixing the cross-boot entry-set digest defect
        // that stranded the Odin. This test previously asserted `assertTrue` here: that a
        // cross-boot receipt digesting the COMPLETE captured set authenticates even when that plan
        // has no persistent axis at all. That matched the pre-2026-08-30 full-set rule, but the
        // restorable-axis check in RestoreGuardianRunnerEngine.confirmStock retired it -- a cross-boot restore
        // with an empty eligible (persistent-axis) set now emits NO stock-confirmed receipt at
        // all ("fails CLOSED", by that function's own KDoc), because a full-set digest for axes
        // nothing was permitted to write is not evidence of anything. This assertion was never
        // updated to match, so the verifier kept silently accepting a receipt shape the producer
        // can no longer create -- a second, dormant divergence between producer and verifier, never
        // reachable in practice only because nothing legitimate reaches it. See the companion test
        // below for the corrected positive case.
        val value = envelope(entries = listOf(sysfs(1)))
        val stock = stock(value).copy(
            recoveryBootIdentity = "boot.2",
            restoreScope = GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
        )
        assertFalse(
            "a zero-persistent-axis plan has no receipt shape confirmStock can legitimately " +
                "produce for this scope; the verifier must fail closed, never fall back to a " +
                "full-set comparison nobody asked it to make",
            RestoreGuardianCrypto.verifyStockConfirmedReceipt(
                RestoreGuardianCrypto.signStockConfirmedReceipt(stock, sessionSigner),
                sessionKey,
                value,
            ),
        )
        // RENAMED 2026-09-06 (policy): this is the empty-eligible-set case specifically --
        // zero persistent axes -- which now names itself "entry_set_empty", distinctly from a
        // genuine digest mismatch's "entry_set". The disposition (refuse) is unchanged.
        assertEquals(
            "scope:entry_set_empty",
            RestoreGuardianCrypto.stockConfirmedReceiptRefusal(
                RestoreGuardianCrypto.signStockConfirmedReceipt(stock, sessionSigner),
                sessionKey,
                value,
            ),
        )
    }

    @Test
    fun `cross boot terminal crypto accepts a receipt digested over the persistent axis alone`() {
        // The corrected positive case, added alongside the fix: a plan with a persistent (fan)
        // axis restored cross-boot digests only that axis
        // (RestoreGuardianRunnerEngine.confirmStock's `axesThisRestoreMayTouch`), and the verifier
        // must accept precisely that -- never the complete captured set, and never nothing.
        // Reproduces, at the crypto layer with a REAL signature, the exact shape measured live on
        // the Odin: entryCount=4 (3 sysfs + 1 fan), scope=CROSS_BOOT..., receipt digest ==
        // digest of the fan entry alone, receipt digest != digest of all 4.
        val value = envelope()
        val eligible = value.restoreEntries.filter { it.isPersistentGuardianAxis() }
        val stock = stock(value).copy(
            recoveryBootIdentity = "boot.2",
            restoreScope = GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
            verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(eligible),
        )
        val exited = exited(value, stock.digestSha256()).copy(
            recoveryBootIdentity = "boot.2",
            restoreScope = GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
            verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(eligible),
        )
        assertTrue(
            RestoreGuardianCrypto.verifyStockConfirmedReceipt(
                RestoreGuardianCrypto.signStockConfirmedReceipt(stock, sessionSigner),
                sessionKey,
                value,
            ),
        )
        assertTrue(
            RestoreGuardianCrypto.verifyExitedReceipt(
                RestoreGuardianCrypto.signExitedReceipt(exited, sessionSigner),
                sessionKey,
                value,
            ),
        )
        // CONTROL: the full-set digest must NOT also authenticate -- there is exactly one right
        // digest for a given scope, never two.
        assertFalse(
            "the full-set digest must not satisfy a cross-boot scope check -- only the " +
                "eligible-axis digest may",
            RestoreGuardianCrypto.verifyStockConfirmedReceipt(
                RestoreGuardianCrypto.signStockConfirmedReceipt(
                    stock.copy(
                        verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(value.restoreEntries),
                    ),
                    sessionSigner,
                ),
                sessionKey,
                value,
            ),
        )
    }

    @Test
    fun `terminalScopeRefusal fails closed on an empty eligible set even against a digest that would otherwise pass`() {
        // ADDED 2026-09-05, on gate feedback: this branch was exercised incidentally by the "zero
        // persistent axis plan" test above but never pinned as its OWN deliberate case. It is
        // deliberate, not a side effect of the digest comparison: a plan with no persistent axis has
        // no receipt shape `RestoreGuardianRunnerEngine.confirmStock` can legitimately produce for
        // CROSS_BOOT scope (its own `entries.isEmpty()` guard never emits one), so this must refuse
        // UNCONDITIONALLY -- not "when the digest happens to mismatch", but always, because no digest
        // is a legitimate answer here. Proven by supplying the exact digest the pre-fix verifier
        // would have accepted (the full captured set) and showing it still refuses.
        //
        // RENAMED 2026-09-06 (policy): the refusal string is now "entry_set_empty", not
        // "entry_set" -- see the CONTROL assertion below, which pins that a genuine non-empty-set
        // digest mismatch still returns the ORIGINAL "entry_set" name, so the two situations
        // ("nothing was verifiable" vs "these two digests disagree") cannot collapse back into one
        // string without one of these two assertions failing.
        val value = envelope(entries = listOf(sysfs(1)))
        val theDigestPreFixWouldHaveAccepted = GuardianRestoreEntrySetDigest.of(value.restoreEntries)
        assertEquals(
            "an empty eligible set must refuse even when handed a digest that verifies against " +
                "SOMETHING real -- there is no digest that can legitimately settle 'nothing was eligible' " +
                "-- and must name itself distinctly from a genuine digest mismatch",
            "entry_set_empty",
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                recoveryBootIdentity = "boot.2",
                scope = GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
                verifiedStockEntrySetSha256 = theDigestPreFixWouldHaveAccepted,
            ),
        )
        // And there is no way to construct the "correct" digest for an empty set to pass instead --
        // GuardianRestoreEntrySetDigest.of refuses an empty list outright, by design (see
        // RestoreGuardianRunnerEngine.confirmStock's "empty case is NOT settled here" comment).
        assertRejected { GuardianRestoreEntrySetDigest.of(emptyList()) }
        // CONTROL: a NON-empty eligible set with a genuinely wrong digest must keep the ORIGINAL
        // "entry_set" name -- this is what fails if a future edit folds the two cases back together.
        assertEquals(
            "a non-empty eligible set with a wrong digest is a real mismatch, not an empty scope, " +
                "and must not be renamed to entry_set_empty",
            "entry_set",
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                recoveryBootIdentity = value.bootIdentity,
                scope = GuardianTerminalRestoreScope.SAME_BOOT_FULL,
                verifiedStockEntrySetSha256 = sha("wrong-digest"),
            ),
        )
    }

    @Test
    fun `terminalScopeRefusal names same-boot-after-reboot and cross-boot-same-boot distinctly`() {
        // Neither of the scope check's other two refusal strings was ever asserted by NAME, in main
        // or test, before this addition (grepped: RestoreGuardianCrypto.kt only, zero test hits).
        // The `boot.3` exercise elsewhere in this file only proved SOME refusal fired --
        // `terminalReceiptRefusal`'s "boot_identity" clause runs first and short-circuits before the
        // scope check is ever reached, so that assertion could not have told a scope refusal apart
        // from an identity refusal. These call `terminalScopeRefusal` directly, bypassing the
        // identity check entirely, so a future edit that stops naming either string fails HERE.
        val value = envelope()
        val fullDigest = GuardianRestoreEntrySetDigest.of(value.restoreEntries)
        val persistentDigest = GuardianRestoreEntrySetDigest.of(value.restoreEntries.filter { it.isPersistentGuardianAxis() })

        // SAME_BOOT_FULL claiming a recovery boot other than the one that armed it: a same-boot
        // receipt is only meaningful if it was produced on the boot it claims.
        assertEquals(
            "same_boot_receipt_after_reboot",
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                recoveryBootIdentity = "a-different-boot",
                scope = GuardianTerminalRestoreScope.SAME_BOOT_FULL,
                verifiedStockEntrySetSha256 = fullDigest,
            ),
        )
        // CONTROL: the instrument can see the opposite -- identical envelope and digest, correct
        // recovery boot, verifies clean.
        assertEquals(
            null,
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                recoveryBootIdentity = value.bootIdentity,
                scope = GuardianTerminalRestoreScope.SAME_BOOT_FULL,
                verifiedStockEntrySetSha256 = fullDigest,
            ),
        )

        // CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK claiming the SAME boot as the envelope: a
        // cross-boot receipt is only meaningful if the recovery genuinely happened on a later boot.
        assertEquals(
            "cross_boot_receipt_same_boot",
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                recoveryBootIdentity = value.bootIdentity,
                scope = GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
                verifiedStockEntrySetSha256 = persistentDigest,
            ),
        )
        // CONTROL: identical envelope and digest, genuinely different recovery boot, verifies clean.
        assertEquals(
            null,
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                recoveryBootIdentity = "a-different-boot",
                scope = GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
                verifiedStockEntrySetSha256 = persistentDigest,
            ),
        )
    }

    @Test
    fun `golden envelope two and terminal three canonical vectors cover ready stock exit and pin`() {
        val value = envelope()
        val key = GuardianSessionPublicKey.fromSec1("046B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C2964FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val ready = receipt(value).copy(sessionPublicKey = key)
        val stock = stock(value).copy(sessionKeySha256 = key.sha256())
        val exited = exited(value, stock.digestSha256()).copy(sessionKeySha256 = key.sha256())
        val pin = RestoreGuardianSessionPin(envelopeSha256 = value.digestSha256(), sessionKeySha256 = key.sha256(), readyReceiptSha256 = sha("ready"), readyIssuedElapsedRealtimeMs = 1, pinnedElapsedRealtimeMs = 2)
        assertEquals(2, ready.schemaVersion)
        assertEquals(3, stock.schemaVersion)
        assertEquals(3, exited.schemaVersion)
        assertEquals(2, pin.schemaVersion)
        assertEquals("1992f85005fa279e7c91256895d91d197cff027c26ef72fa7ef1c28272eda162", GuardianCanonicalCodec.sha256(ready.canonicalBytes()))
        assertEquals("c2bd0938b4bad3e6cb3b7f9206c1a377b3434aa3764f38f275cf299533c92b61", GuardianCanonicalCodec.sha256(stock.canonicalBytes()))
        assertEquals("2cce5b385fbdeb50bbf1fbaa2c361515cf2f419beba0a5100795a548697e991d", GuardianCanonicalCodec.sha256(exited.canonicalBytes()))
        assertEquals("fea52655b011a424244cb127f777fe89d22acbe9ddf9ae903cf645637d8ca7bd", GuardianCanonicalCodec.sha256(pin.canonicalBytes()))
    }

    @Test
    fun `app signing types cannot enter ready or terminal crypto APIs`() {
        assertFalse(GuardianSessionSigner::class.java.isAssignableFrom(EcdsaP256Signer::class.java))
        RestoreGuardianCrypto::class.java.declaredMethods
            .filter { it.name in setOf("signReadyReceipt", "signStockConfirmedReceipt", "signExitedReceipt", "verifyReadyReceipt", "verifyStockConfirmedReceipt", "verifyExitedReceipt") }
            .forEach { method ->
                assertFalse(method.parameterTypes.any { it == RestoreGuardianSigner::class.java || it == PinnedRestoreGuardianVerifier::class.java })
            }
    }

    @Test
    fun `heartbeat rejects every stale identity replay clock extension and post terminal state`() {
        val value = envelope()
        val initial = GuardianHeartbeatState()
        val beat = heartbeat(value, counter = 1, elapsed = 100)
        val signed = RestoreGuardianCrypto.signHeartbeat(beat, signer)
        assertTrue(RestoreGuardianCrypto.verifyHeartbeat(signed, trusted, value))
        assertFalse(
            RestoreGuardianCrypto.verifyHeartbeat(
                signed,
                GuardianTestKeyAuthority.create(sha("other-certificate"), keyPair).available.envelopeVerifier(),
                value,
            ),
        )
        assertEquals(HeartbeatDecision.ACCEPT, RestoreGuardianHeartbeatPolicy.evaluate(value, initial, beat, 110, true))
        val advanced = RestoreGuardianHeartbeatPolicy.advanced(initial, beat)
        assertEquals(HeartbeatDecision.REPLAY_OR_REORDER, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, beat, 120, true))
        assertEquals(HeartbeatDecision.REPLAY_OR_REORDER, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 1, 121), 121, true))
        assertEquals(HeartbeatDecision.CLOCK_REGRESSION, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 2, 99), 121, true))
        assertEquals(HeartbeatDecision.CLOCK_REGRESSION, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 2, 130), 121, true))
        assertEquals(HeartbeatDecision.DEADLINE_MISMATCH, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 2, 121, deadline = 20_000), 121, true))
        assertEquals(HeartbeatDecision.EXPIRED, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 2, 121), 10_001, true))
        assertEquals(HeartbeatDecision.EXPIRED, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 2, 121), 10_000, true))
        assertEquals(HeartbeatDecision.INVALID_SIGNATURE, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, heartbeat(value, 2, 121), 121, false))
        assertEquals(HeartbeatDecision.TERMINAL, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced.copy(terminalState = GuardianTerminalState.TERMINAL), heartbeat(value, 2, 121), 121, true))
        listOf(
            heartbeat(value, 2, 121).copy(helperBuildIdentity = "helper.2"),
            heartbeat(value, 2, 121).copy(bootIdentity = "boot.2"),
            heartbeat(value, 2, 121).copy(generation = 9),
            heartbeat(value, 2, 121).copy(requestId = "request.2"),
            heartbeat(value, 2, 121).copy(transactionId = "transaction.2"),
            heartbeat(value, 2, 121).copy(taskNonce = "b".repeat(32)),
            heartbeat(value, 2, 121).copy(envelopeSha256 = sha("wrong")),
        ).forEach { assertEquals(HeartbeatDecision.IDENTITY_MISMATCH, RestoreGuardianHeartbeatPolicy.evaluate(value, advanced, it, 121, true)) }
    }

    @Test
    fun `certificate nonce and identifier validation fail before signing`() {
        assertRejected { envelope().copy(tweakerCertificateSha256 = "UPPER") }
        assertRejected { envelope().copy(taskNonce = "not-a-nonce") }
        assertRejected { envelope().copy(bootIdentity = "boot with a space") }
        assertRejected { envelope().copy(generation = 0) }
        assertRejected { envelope().copy(hardDeadlineElapsedRealtimeMs = 0) }
    }

    private fun envelope(entries: List<RestoreGuardianRestoreEntry> = listOf(sysfs(3), fanPut(2))): RestoreGuardianEnvelope =
        RestoreGuardianEnvelope(
            helperBuildIdentity = "helper.1", tweakerCertificateSha256 = sha("certificate"), hardwareFingerprint = "hardware.1",
            deviceProfile = "rp5.1", bootIdentity = "boot.1", generation = 7, requestId = "request.1", transactionId = "transaction.1",
            taskNonce = "a".repeat(32), topologySha256 = sha("topology"), snapshotSha256 = sha("snapshot"), planSha256 = sha("plan"),
            hardDeadlineElapsedRealtimeMs = 10_000, restoreEntries = entries,
        )

    private fun sysfs(ordinal: Int, id: String = "cpu:1:scaling_max_freq") = RestoreGuardianSysfsEntry(
        applyOrdinal = ordinal, logicalId = id, canonicalSysfsPath = "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq",
        expectedStock = RestoreGuardianStockState.PresentSysfs(1_200_000, "644", 0, 0), rendererAppliedIntermediates = listOf(2_000_000),
    )

    private fun fanPut(ordinal: Int) = RestoreGuardianFanPutEntry(
        applyOrdinal = ordinal, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = listOf(2),
    )

    private fun receipt(value: RestoreGuardianEnvelope) = RestoreGuardianReadyReceipt(
        helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity, generation = value.generation,
        requestId = value.requestId, transactionId = value.transactionId, taskNonce = value.taskNonce,
        envelopeSha256 = value.digestSha256(), sessionPublicKey = sessionKey, issuedElapsedRealtimeMs = 1,
    )

    private fun heartbeat(value: RestoreGuardianEnvelope, counter: Long, elapsed: Long, deadline: Long = value.hardDeadlineElapsedRealtimeMs) = RestoreGuardianHeartbeat(
        helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity, generation = value.generation,
        requestId = value.requestId, transactionId = value.transactionId, taskNonce = value.taskNonce,
        envelopeSha256 = value.digestSha256(), counter = counter, elapsedRealtimeMs = elapsed, hardDeadlineElapsedRealtimeMs = deadline,
    )

    private fun stock(value: RestoreGuardianEnvelope) = RestoreGuardianStockConfirmedReceipt(
        helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity, generation = value.generation,
        requestId = value.requestId, transactionId = value.transactionId, taskNonce = value.taskNonce,
        envelopeSha256 = value.digestSha256(), sessionKeySha256 = sessionKey.sha256(),
        verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(value.restoreEntries), firstReadbackElapsedRealtimeMs = 100, secondReadbackElapsedRealtimeMs = 175, issuedElapsedRealtimeMs = 200,
    )
    private fun exited(value: RestoreGuardianEnvelope, stockDigest: String) = RestoreGuardianExitedReceipt(
        helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity, generation = value.generation,
        requestId = value.requestId, transactionId = value.transactionId, taskNonce = value.taskNonce,
        envelopeSha256 = value.digestSha256(), sessionKeySha256 = sessionKey.sha256(),
        verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(value.restoreEntries), stockConfirmedReceiptSha256 = stockDigest, issuedElapsedRealtimeMs = 250,
    )

    private fun sha(value: String) = GuardianCanonicalCodec.sha256(value.toByteArray())

    private fun assertRejected(block: () -> Unit) {
        try { block(); throw AssertionError("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
