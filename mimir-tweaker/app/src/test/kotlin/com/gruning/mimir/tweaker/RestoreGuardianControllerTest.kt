package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardianControllerTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val certificate = digest("certificate")
    private val available = GuardianTestKeyAuthority.create(certificate, pair).available
    private val pinned = available.envelopeVerifier()
    private val signer = EcdsaP256Signer(pair.private)
    private val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
    private val sessionSigner = EcdsaP256GuardianSessionSigner(sessionPair.private)

    @Test fun `only exact signed ready grants mutation authority and dispatches stage synchronously watcher detached`() {
        val fixture = Fixture()
        val outcome = fixture.controller().armToReady(fixture.signed, fixture.expected)

        assertTrue(outcome is GuardianArmAdmission.Ready)
        assertTrue(outcome.mutationAuthority)
        assertEquals(listOf(false), fixture.stageDetached)
        assertEquals(listOf(true), fixture.watcherDetached)
        assertEquals(1, fixture.readyCalls)
    }

    @Test fun `wrong envelope key signature identity or boot rejects before any dispatch`() {
        val otherPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        listOf(
            Fixture().also { it.signed = RestoreGuardianCrypto.signEnvelope(it.envelope, EcdsaP256Signer(otherPair.private)) },
            Fixture().also { it.expected = it.expected.copy(generation = 8) },
            Fixture().also { it.liveBoot = "other-boot" },
        ).forEach { fixture ->
            val outcome = fixture.controller().armToReady(fixture.signed, fixture.expected)
            assertTrue(outcome is GuardianArmAdmission.ArmRejected)
            assertFalse(outcome.mutationAuthority)
            assertTrue(fixture.stageDetached.isEmpty())
            assertTrue(fixture.watcherDetached.isEmpty())
            assertEquals(0, fixture.readyCalls)
        }
    }

    @Test fun `expired deadline rejects before arm or dispatch`() {
        val fixture = Fixture(now = 10_000)
        val outcome = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRejectReason.DEADLINE_EXPIRED, (outcome as GuardianArmAdmission.ArmRejected).reason)
        assertEquals(GuardianArmStoreLoad.Empty, fixture.store.load(fixture.expected))
        assertTrue(fixture.stageDetached.isEmpty())
    }

    @Test fun `receipt must be both cryptographically valid and exactly matched`() {
        val fixture = Fixture()
        val mismatched = fixture.ready.receipt.copy(generation = 99)
        fixture.ready = RestoreGuardianCrypto.signReadyReceipt(mismatched, sessionSigner)
        val outcome = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRetainedReason.READY_UNCONFIRMED, (outcome as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(outcome.mutationAuthority)
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `invalid receipt signature retains durable arm after confirmed launch`() {
        val fixture = Fixture()
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        fixture.ready = RestoreGuardianCrypto.signReadyReceipt(fixture.ready.receipt, EcdsaP256GuardianSessionSigner(other.private))
        val outcome = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertTrue(outcome is GuardianArmAdmission.ArmRetained)
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `every READY channel mismatch and forbidden writer retain staged truth with no authority`() {
        val baseline = GuardianReadyChannelEvidence(0, 0, "600", "u:object_r:shell_data_file:s0", true)
        val mismatches = listOf(
            baseline.copy(ownerUid = 1),
            baseline.copy(ownerGid = 1),
            baseline.copy(modeOctal = "640"),
            baseline.copy(seLinuxLabel = "u:object_r:other:s0"),
            baseline.copy(exactBytesConfirmed = false),
        )
        mismatches.forEach { evidence ->
            val fixture = Fixture().apply { readyLoad = GuardianReadyLoad.Received(ready, evidence) }
            val result = fixture.controller().armToReady(fixture.signed, fixture.expected)
            assertEquals(GuardianArmRetainedReason.READY_CHANNEL_REJECTED, (result as GuardianArmAdmission.ArmRetained).reason)
            assertFalse(result.mutationAuthority)
            assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedStaged)
        }
        val forbidden = Fixture().apply {
            channelExpectation = GuardianReadyChannelExpectation(0, 0, "600", "u:object_r:shell_data_file:s0", true, setOf(0))
        }
        val forbiddenResult = forbidden.controller().armToReady(forbidden.signed, forbidden.expected)
        assertEquals(GuardianArmRetainedReason.READY_CHANNEL_REJECTED, (forbiddenResult as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(forbiddenResult.mutationAuthority)
        assertTrue(forbidden.store.load(forbidden.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `reflected app public key is rejected before pinning or activation`() {
        val fixture = Fixture()
        val reflected = GuardianSessionPublicKey.fromPublicKey(pair.public)
        val receipt = fixture.ready.receipt.copy(sessionPublicKey = reflected)
        val signedByPinnedAppKey = RestoreGuardianCrypto.signReadyReceipt(receipt, EcdsaP256GuardianSessionSigner(pair.private))
        fixture.ready = requireNotNull(
            GuardianReadyWireCodec.decode(
                requireNotNull(GuardianReadyWireCodec.encode(signedByPinnedAppKey.receipt.canonicalBytes(), signedByPinnedAppKey.signature)),
            ),
        )
        val result = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRetainedReason.READY_KEY_REJECTED, (result as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(result.mutationAuthority)
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `channel expectation pins forbidden uid set across caller and returned set mutation`() {
        val callerSet = mutableSetOf(0)
        val expectation = GuardianReadyChannelExpectation(0, 0, "600", "u:object_r:shell_data_file:s0", true, callerSet)
        callerSet.clear()
        runCatching { (expectation.forbiddenWriterUids as MutableSet<Int>).clear() }
        val fixture = Fixture().apply { channelExpectation = expectation }
        val result = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRetainedReason.READY_CHANNEL_REJECTED, (result as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(result.mutationAuthority)
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `pin throw is retained ambiguous with staged truth and no active handle`() {
        val fixture = Fixture().apply { pinThrows = true }
        val result = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRetainedReason.SESSION_PIN_AMBIGUOUS, (result as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(result.mutationAuthority)
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `pin rejected and unreadable reload retain zero mutation authority`() {
        val rejected = Fixture().apply { pinOverride = GuardianSessionPinWrite.Rejected }
        val rejectedResult = rejected.controller().armToReady(rejected.signed, rejected.expected)
        assertEquals(GuardianArmRetainedReason.SESSION_PIN_REJECTED, (rejectedResult as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(rejectedResult.mutationAuthority)
        assertTrue(rejected.store.load(rejected.expected) is GuardianArmStoreLoad.LoadedStaged)

        val unreadable = Fixture().apply { unreadableAfterPin = true }
        val unreadableResult = unreadable.controller().armToReady(unreadable.signed, unreadable.expected)
        assertEquals(GuardianArmRetainedReason.SESSION_PIN_UNREADABLE, (unreadableResult as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(unreadableResult.mutationAuthority)
    }

    @Test fun `post pin authoritative reload throw retains zero mutation authority`() {
        val fixture = Fixture().apply { reloadThrowsAfterPin = true }

        val result = fixture.controller().armToReady(fixture.signed, fixture.expected)

        assertEquals(GuardianArmRetainedReason.SESSION_PIN_UNREADABLE, (result as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(result.mutationAuthority)
        fixture.reloadThrowsAfterPin = false
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
    }

    @Test fun `terminal verifier is derived from durable loaded ready rather than caller ready objects`() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val otherKey = GuardianSessionPublicKey.fromPublicKey(other.public)
        val otherSigner = EcdsaP256GuardianSessionSigner(other.private)
        val fixture = Fixture()
        val reloadedReceipt = fixture.ready.receipt.copy(sessionPublicKey = otherKey)
        val reloadedSigned = RestoreGuardianCrypto.signReadyReceipt(reloadedReceipt, otherSigner)
        val reloadedPin = RestoreGuardianCrypto.signSessionPin(
            RestoreGuardianSessionPin(
                envelopeSha256 = fixture.envelope.digestSha256(), sessionKeySha256 = otherKey.sha256(),
                readyReceiptSha256 = GuardianCanonicalCodec.sha256(reloadedReceipt.canonicalBytes()),
                readyIssuedElapsedRealtimeMs = fixture.time, pinnedElapsedRealtimeMs = fixture.time,
            ), signer,
        )
        fixture.reloadOverride = GuardianArmStoreLoad.LoadedReady(fixture.signed, otherKey, reloadedSigned, reloadedPin)
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 900
        val stock = RestoreGuardianCrypto.signStockConfirmedReceipt(
            RestoreGuardianStockConfirmedReceipt(
                helperBuildIdentity = fixture.envelope.helperBuildIdentity, bootIdentity = fixture.envelope.bootIdentity,
                generation = fixture.envelope.generation, requestId = fixture.envelope.requestId,
                transactionId = fixture.envelope.transactionId, taskNonce = fixture.envelope.taskNonce,
                envelopeSha256 = fixture.envelope.digestSha256(), sessionKeySha256 = otherKey.sha256(),
                verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(fixture.envelope.restoreEntries),
                firstReadbackElapsedRealtimeMs = 500, secondReadbackElapsedRealtimeMs = 575, issuedElapsedRealtimeMs = 600,
            ), otherSigner,
        )
        val exited = RestoreGuardianCrypto.signExitedReceipt(
            RestoreGuardianExitedReceipt(
                helperBuildIdentity = fixture.envelope.helperBuildIdentity, bootIdentity = fixture.envelope.bootIdentity,
                generation = fixture.envelope.generation, requestId = fixture.envelope.requestId,
                transactionId = fixture.envelope.transactionId, taskNonce = fixture.envelope.taskNonce,
                envelopeSha256 = fixture.envelope.digestSha256(), sessionKeySha256 = otherKey.sha256(),
                verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(fixture.envelope.restoreEntries),
                stockConfirmedReceiptSha256 = stock.receipt.digestSha256(), issuedElapsedRealtimeMs = 650,
            ), otherSigner,
        )
        fixture.terminalLoad = GuardianTerminalLoad.Received(stock, exited)
        assertEquals(GuardianTerminalAcknowledgement.Cleared, fixture.controller().acknowledgeTerminal(ready.handle))
    }

    @Test fun `rejected dispatch proves no watcher and clears arm but ambiguity retains it`() {
        val rejected = Fixture().apply { stageResult = GuardianDispatchResult.REJECTED }
        val rejectedResult = rejected.controller().armToReady(rejected.signed, rejected.expected)
        assertEquals(GuardianArmRejectReason.STAGE_DISPATCH_REJECTED, (rejectedResult as GuardianArmAdmission.ArmRejected).reason)
        assertEquals(GuardianArmStoreLoad.Empty, rejected.store.load(rejected.expected))
        assertTrue(rejected.watcherDetached.isEmpty())

        val ambiguous = Fixture().apply { stageResult = GuardianDispatchResult.AMBIGUOUS }
        val ambiguousResult = ambiguous.controller().armToReady(ambiguous.signed, ambiguous.expected)
        assertEquals(GuardianArmRetainedReason.STAGE_DISPATCH_AMBIGUOUS, (ambiguousResult as GuardianArmAdmission.ArmRetained).reason)
        assertTrue(ambiguous.store.load(ambiguous.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `stage-confirmed launch rejection ambiguity or ready timeout retain durable arm`() {
        val rejected = Fixture().apply { watchResult = GuardianDispatchResult.REJECTED }
        assertEquals(GuardianArmRetainedReason.GUARDIAN_LAUNCH_AMBIGUOUS, (rejected.controller().armToReady(rejected.signed, rejected.expected) as GuardianArmAdmission.ArmRetained).reason)
        assertTrue(rejected.store.load(rejected.expected) is GuardianArmStoreLoad.LoadedStaged)

        val ambiguous = Fixture().apply { watchResult = GuardianDispatchResult.AMBIGUOUS }
        assertEquals(GuardianArmRetainedReason.GUARDIAN_LAUNCH_AMBIGUOUS, (ambiguous.controller().armToReady(ambiguous.signed, ambiguous.expected) as GuardianArmAdmission.ArmRetained).reason)
        assertTrue(ambiguous.store.load(ambiguous.expected) is GuardianArmStoreLoad.LoadedStaged)

        val timeout = Fixture().apply { readyLoad = GuardianReadyLoad.TimedOut }
        assertEquals(GuardianArmRetainedReason.READY_UNCONFIRMED, (timeout.controller().armToReady(timeout.signed, timeout.expected) as GuardianArmAdmission.ArmRetained).reason)
        assertTrue(timeout.store.load(timeout.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `ready load receives a fixed five-second bounded deadline with overflow safety`() {
        val bounded = Fixture(now = 500).apply { envelopeOverrideDeadline = 9_999 }
        val boundedResult = bounded.controller().armToReady(bounded.signed, bounded.expected)
        assertTrue(boundedResult is GuardianArmAdmission.Ready)
        assertEquals(listOf(5_500L), bounded.readyDeadlines)

        val nearMax = Fixture(now = Long.MAX_VALUE - 2_000).apply {
            envelopeOverrideDeadline = Long.MAX_VALUE
        }
        assertTrue(nearMax.controller().armToReady(nearMax.signed, nearMax.expected) is GuardianArmAdmission.Ready)
        assertEquals(listOf(Long.MAX_VALUE), nearMax.readyDeadlines)
    }

    @Test fun `active or reentrant second arm is rejected without a second stage`() {
        val fixture = Fixture()
        val controller = fixture.controller()
        assertTrue(controller.armToReady(fixture.signed, fixture.expected) is GuardianArmAdmission.Ready)
        assertEquals(GuardianArmRejectReason.CONCURRENT_ARM, (controller.armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.ArmRejected).reason)
        assertEquals(1, fixture.stageDetached.size)
    }

    @Test fun `untrusted installed certificate rejects before durable arm`() {
        val fixture = Fixture().apply { apk = apk.copy(actualSigningCertificateSha256 = digest("other")) }
        val result = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRejectReason.INSTALLED_APK_UNTRUSTED, (result as GuardianArmAdmission.ArmRejected).reason)
        assertEquals(GuardianArmStoreLoad.Empty, fixture.store.load(fixture.expected))
    }

    @Test fun `prearm port failure rejects while postarm dispatch and ready failures retain`() {
        val prearm = Fixture().apply { sourceThrows = true }
        assertEquals(GuardianArmRejectReason.PREARM_PORT_FAILURE, (prearm.controller().armToReady(prearm.signed, prearm.expected) as GuardianArmAdmission.ArmRejected).reason)
        assertTrue(prearm.stageDetached.isEmpty())

        val stage = Fixture().apply { stageThrows = true }
        assertEquals(GuardianArmRetainedReason.STAGE_DISPATCH_AMBIGUOUS, (stage.controller().armToReady(stage.signed, stage.expected) as GuardianArmAdmission.ArmRetained).reason)
        assertTrue(stage.store.load(stage.expected) is GuardianArmStoreLoad.LoadedStaged)

        val ready = Fixture().apply { readyThrows = true }
        assertEquals(GuardianArmRetainedReason.READY_UNCONFIRMED, (ready.controller().armToReady(ready.signed, ready.expected) as GuardianArmAdmission.ArmRetained).reason)
        assertTrue(ready.store.load(ready.expected) is GuardianArmStoreLoad.LoadedStaged)
    }

    @Test fun `P-256 signer and verifier require the exact secp256r1 domain`() {
        val exact = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        EcdsaP256Signer(exact.private)
        EcdsaP256Verifier(exact.public)

        val exactParameters = (exact.public as ECPublicKey).params
        val sameSizeWrongDomain = ECParameterSpec(
            exactParameters.curve,
            exactParameters.generator,
            exactParameters.order,
            2,
        )
        assertIllegalArgument { EcdsaP256Signer(FakeEcPrivateKey(sameSizeWrongDomain)) }
        assertIllegalArgument { EcdsaP256Verifier(FakeEcPublicKey(sameSizeWrongDomain)) }
    }

    @Test fun `returned ready handle cannot mutate active pinned signature or restore truth`() {
        val fixture = Fixture()
        val callerEntries = fixture.envelope.restoreEntries.toMutableList()
        val callerEnvelope = fixture.envelope.copy(restoreEntries = callerEntries)
        fixture.signed = RestoreGuardianCrypto.signEnvelope(callerEnvelope, signer)
        fixture.expected = expected(callerEnvelope)
        fixture.ready = RestoreGuardianCrypto.signReadyReceipt(receipt(callerEnvelope, fixture.time), sessionSigner)

        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        val exposed = ready.handle.signedEnvelope
        val canonicalBefore = exposed.envelope.canonicalBytes()
        val signatureBefore = exposed.signature.copyOf()
        fixture.signed.signature[0] = (fixture.signed.signature[0].toInt() xor 1).toByte()
        callerEntries.clear()
        exposed.signature[0] = (exposed.signature[0].toInt() xor 1).toByte()
        runCatching { (exposed.envelope.restoreEntries as MutableList<RestoreGuardianRestoreEntry>).clear() }

        assertTrue(canonicalBefore.contentEquals(ready.handle.signedEnvelope.envelope.canonicalBytes()))
        assertTrue(signatureBefore.contentEquals(ready.handle.signedEnvelope.signature))
        assertEquals(1, ready.handle.signedEnvelope.envelope.restoreEntries.size)
        fixture.time = 600
        assertEquals(GuardianHeartbeatPublication.Confirmed, fixture.publish(ready.handle, fixture.heartbeat(1, 600)))
    }

    @Test fun `preexisting durable arm truth retains rather than implying empty authority`() {
        val fixture = Fixture()
        assertEquals(GuardianArmStoreWrite.Armed, fixture.store.arm(fixture.signed))
        val result = fixture.controller().armToReady(fixture.signed, fixture.expected)
        assertEquals(GuardianArmRetainedReason.ARM_STORE_AMBIGUOUS, (result as GuardianArmAdmission.ArmRetained).reason)
        assertFalse(result.mutationAuthority)
        assertTrue(fixture.stageDetached.isEmpty())
    }

    @Test fun `exact active handle publishes signed heartbeat and preserves immutable deadline`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        val heartbeat = fixture.heartbeat(counter = 1, elapsed = 600)
        fixture.time = 600

        assertEquals(GuardianHeartbeatPublication.Confirmed, fixture.publish(ready.handle, heartbeat))
        assertEquals(listOf(1L), fixture.dispatchedHeartbeatCounters)
        assertEquals(10_000L, ready.handle.signedEnvelope.envelope.hardDeadlineElapsedRealtimeMs)
    }

    @Test fun `wrong or stale handle cannot dispatch against active guardian`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        val copiedHandle = GuardianReadyHandle(
            ready.handle.expectedIdentity,
            ready.handle.signedEnvelope,
            ready.handle.readySessionSha256,
        )

        assertEquals(
            GuardianHeartbeatPublication.Rejected(GuardianHeartbeatRejectReason.INACTIVE_HANDLE),
            fixture.publish(copiedHandle, fixture.heartbeat(counter = 1, elapsed = 600)),
        )
        assertTrue(fixture.dispatchedHeartbeatCounters.isEmpty())
    }

    @Test fun `bad signature identity boot clock and deadline require restore without dispatch`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val wrongKey = RestoreGuardianCrypto.signHeartbeat(fixture.heartbeatPayload(1, 600), EcdsaP256Signer(other.private))
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.INVALID_SIGNATURE),
            fixture.publish(ready.handle, wrongKey),
        )
        fixture.time = 600
        val wrongGeneration = RestoreGuardianCrypto.signHeartbeat(
            fixture.heartbeatPayload(2, 600).copy(generation = 8),
            signer,
        )
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.IDENTITY_MISMATCH),
            fixture.publish(ready.handle, wrongGeneration),
        )
        fixture.liveBoot = "other-boot"
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.BOOT_MISMATCH),
            fixture.publish(ready.handle, fixture.heartbeat(2, 601)),
        )
        fixture.liveBoot = "boot.1"
        fixture.time = 10_000
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.EXPIRED),
            fixture.publish(ready.handle, fixture.heartbeat(3, 602)),
        )
        assertTrue(fixture.dispatchedHeartbeatCounters.isEmpty())
    }

    @Test fun `replay reorder regression future timestamp and deadline replacement require restore without dispatch`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 600
        assertEquals(GuardianHeartbeatPublication.Confirmed, fixture.publish(ready.handle, fixture.heartbeat(2, 600)))
        fixture.time = 601
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.REPLAY_OR_REORDER),
            fixture.publish(ready.handle, fixture.heartbeat(2, 601)),
        )
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.REPLAY_OR_REORDER),
            fixture.publish(ready.handle, fixture.heartbeat(1, 601)),
        )
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.CLOCK_REGRESSION),
            fixture.publish(ready.handle, fixture.heartbeat(3, 599)),
        )
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.CLOCK_REGRESSION),
            fixture.publish(ready.handle, fixture.heartbeat(3, 700)),
        )
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.DEADLINE_MISMATCH),
            fixture.publish(ready.handle, fixture.heartbeat(3, 601, deadline = 10_001)),
        )
        assertEquals(listOf(2L), fixture.dispatchedHeartbeatCounters)
    }

    @Test fun `rejected ambiguous or throwing dispatch consumes validated counter without clearing arm`() {
        val fixture = Fixture()
        val controller = fixture.controller()
        val ready = controller.armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 600
        fixture.heartbeatResult = GuardianDispatchResult.REJECTED
        assertEquals(
            GuardianHeartbeatPublication.Rejected(GuardianHeartbeatRejectReason.DISPATCH_REJECTED),
            fixture.publish(ready.handle, fixture.heartbeat(1, 600)),
        )
        fixture.time = 601
        fixture.heartbeatResult = GuardianDispatchResult.AMBIGUOUS
        assertEquals(GuardianHeartbeatPublication.Ambiguous, fixture.publish(ready.handle, fixture.heartbeat(2, 601)))
        fixture.time = 602
        fixture.heartbeatThrows = true
        assertEquals(GuardianHeartbeatPublication.Ambiguous, fixture.publish(ready.handle, fixture.heartbeat(3, 602)))
        fixture.time = 603
        fixture.heartbeatThrows = false
        fixture.heartbeatResult = GuardianDispatchResult.CONFIRMED
        assertEquals(
            GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.REPLAY_OR_REORDER),
            fixture.publish(ready.handle, fixture.heartbeat(3, 603)),
        )
        fixture.time = 604
        assertEquals(GuardianHeartbeatPublication.Confirmed, fixture.publish(ready.handle, fixture.heartbeat(4, 604)))
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.dispatchedHeartbeatCounters)
    }

    @Test fun `chained signed terminal receipts clear once and permanently refuse old heartbeat`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 900
        val stock = fixture.stock(first = 500, second = 575, issued = 600)
        fixture.terminalLoad = GuardianTerminalLoad.Received(stock, fixture.exited(stock.receipt.digestSha256(), 650))
        assertEquals(GuardianTerminalAcknowledgement.Cleared, fixture.controller().acknowledgeTerminal(ready.handle))
        assertEquals(GuardianHeartbeatPublication.Rejected(GuardianHeartbeatRejectReason.INACTIVE_HANDLE), fixture.publish(ready.handle, fixture.heartbeat(1, 700)))
        assertEquals(GuardianArmStoreLoad.Empty, fixture.store.load(fixture.expected))
    }

    @Test fun `terminal verification retains arm until an exact separate disarm`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 900
        val stock = fixture.stock(first = 500, second = 575, issued = 600)
        fixture.terminalLoad = GuardianTerminalLoad.Received(
            stock,
            fixture.exited(stock.receipt.digestSha256(), 650),
        )

        assertEquals(
            GuardianTerminalVerification.Verified(stock.receipt.digestSha256()),
            fixture.controller().verifyTerminal(ready.handle),
        )
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
        assertTrue(fixture.controller().disarm(ready.handle))
        assertEquals(GuardianArmStoreLoad.Empty, fixture.store.load(fixture.expected))
        assertFalse(fixture.controller().disarm(ready.handle))
    }

    @Test fun `stock without valid exited retains arm and refuses heartbeat`() {
        val fixture = Fixture()
        val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 900
        fixture.terminalLoad = GuardianTerminalLoad.Received(fixture.stock(500, 575, 600), null)
        assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED), fixture.controller().acknowledgeTerminal(ready.handle))
        assertEquals(GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.TERMINAL), fixture.publish(ready.handle, fixture.heartbeat(1, 700)))
        assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
    }

    @Test fun `wrong key or tampered exited after valid stock retains arm and permanently refuses heartbeat`() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        listOf<(Fixture, SignedRestoreGuardianStockConfirmedReceipt) -> SignedRestoreGuardianExitedReceipt>(
            { f, s -> RestoreGuardianCrypto.signExitedReceipt(f.exited(s.receipt.digestSha256(), 650).receipt, EcdsaP256GuardianSessionSigner(other.private)) },
            { f, s -> SignedRestoreGuardianExitedReceipt(f.exited(s.receipt.digestSha256(), 650).receipt, f.exited(s.receipt.digestSha256(), 650).signature.also { it[0] = (it[0].toInt() xor 1).toByte() }) },
        ).forEach { invalidExited ->
            val fixture = Fixture(); val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
            fixture.time = 900; val stock = fixture.stock(500, 575, 600)
            fixture.terminalLoad = GuardianTerminalLoad.Received(stock, invalidExited(fixture, stock))
            assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED), fixture.controller().acknowledgeTerminal(ready.handle))
            assertEquals(GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.TERMINAL), fixture.publish(ready.handle, fixture.heartbeat(1, 700)))
            assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
        }
    }

    @Test fun `right key stale stock identity never clears or terminalizes active truth`() {
        listOf<(RestoreGuardianStockConfirmedReceipt) -> RestoreGuardianStockConfirmedReceipt>(
            { it.copy(generation = 8) }, { it.copy(taskNonce = "b".repeat(32)) }, { it.copy(envelopeSha256 = digest("other")) },
        ).forEach { stale ->
            val fixture = Fixture(); val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
            fixture.time = 900; val stock = RestoreGuardianCrypto.signStockConfirmedReceipt(stale(fixture.stock(500, 575, 600).receipt), sessionSigner)
            fixture.terminalLoad = GuardianTerminalLoad.Received(stock, fixture.exited(stock.receipt.digestSha256(), 650))
            assertEquals(GuardianTerminalAcknowledgement.Rejected(GuardianTerminalRejectReason.INVALID_STOCK), fixture.controller().acknowledgeTerminal(ready.handle))
            assertEquals(GuardianHeartbeatPublication.Confirmed, fixture.publish(ready.handle, fixture.heartbeat(1, 700)))
            assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
        }
    }

    @Test fun `broken exited stock digest chain retains active arm and blocks heartbeat`() {
        val fixture = Fixture(); val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
        fixture.time = 900; val stock = fixture.stock(500, 575, 600)
        fixture.terminalLoad = GuardianTerminalLoad.Received(stock, fixture.exited(digest("other-stock"), 650))
        assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED), fixture.controller().acknowledgeTerminal(ready.handle))
        assertEquals(GuardianHeartbeatPublication.RestoreRequired(GuardianHeartbeatRestoreReason.TERMINAL), fixture.publish(ready.handle, fixture.heartbeat(1, 700)))
    }

    @Test fun `stock begins no earlier than controller verified ready rather than self reported ready issuance`() {
        val rejected = Fixture().apply { readyVerificationTime = 600L }
        val rejectedHandle = rejected.controller().armToReady(rejected.signed, rejected.expected) as GuardianArmAdmission.Ready
        rejected.time = 900
        rejected.terminalLoad = GuardianTerminalLoad.Received(rejected.stock(550, 625, 650), rejected.exited(rejected.stock(550, 625, 650).receipt.digestSha256(), 700))
        assertEquals(GuardianTerminalAcknowledgement.Rejected(GuardianTerminalRejectReason.INVALID_STOCK), rejected.controller().acknowledgeTerminal(rejectedHandle.handle))

        val accepted = Fixture().apply { readyVerificationTime = 600L }
        val acceptedHandle = accepted.controller().armToReady(accepted.signed, accepted.expected) as GuardianArmAdmission.Ready
        accepted.time = 900
        val stock = accepted.stock(600, 675, 700)
        accepted.terminalLoad = GuardianTerminalLoad.Received(stock, accepted.exited(stock.receipt.digestSha256(), 750))
        assertEquals(GuardianTerminalAcknowledgement.Cleared, accepted.controller().acknowledgeTerminal(acceptedHandle.handle))
    }

    @Test fun `terminal port is bounded and unavailable timeout ambiguous or throw retain arm`() {
        listOf<GuardianTerminalLoad>(GuardianTerminalLoad.Unavailable, GuardianTerminalLoad.TimedOut, GuardianTerminalLoad.Ambiguous).forEach { load ->
            val fixture = Fixture(); val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
            fixture.time = 900; fixture.terminalLoad = load
            assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE), fixture.controller().acknowledgeTerminal(ready.handle))
            assertEquals(listOf(5_900L), fixture.terminalDeadlines)
            assertTrue(fixture.store.load(fixture.expected) is GuardianArmStoreLoad.LoadedReady)
        }
        val throwing = Fixture(); val ready = throwing.controller().armToReady(throwing.signed, throwing.expected) as GuardianArmAdmission.Ready
        throwing.time = 900; throwing.terminalThrows = true
        assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE), throwing.controller().acknowledgeTerminal(ready.handle))
        assertEquals(listOf(5_900L), throwing.terminalDeadlines)
    }

    @Test fun `terminal time bounds reject 74ms settle future issuance exit before stock and pre ready readback`() {
        listOf(
            Triple(500L, 574L, 600L) to 650L,
            Triple(500L, 575L, 901L) to 950L,
            Triple(500L, 575L, 600L) to 599L,
        ).forEach { (stockTimes, exitIssued) ->
            val fixture = Fixture(); val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
            fixture.time = 900; val stock = fixture.stock(stockTimes.first, stockTimes.second, stockTimes.third)
            fixture.terminalLoad = GuardianTerminalLoad.Received(stock, fixture.exited(stock.receipt.digestSha256(), exitIssued))
            assertTrue(fixture.controller().acknowledgeTerminal(ready.handle) !is GuardianTerminalAcknowledgement.Cleared)
        }
        val futureExit = Fixture(); val futureReady = futureExit.controller().armToReady(futureExit.signed, futureExit.expected) as GuardianArmAdmission.Ready
        futureExit.time = 900; val futureStock = futureExit.stock(500, 575, 600)
        futureExit.terminalLoad = GuardianTerminalLoad.Received(futureStock, futureExit.exited(futureStock.receipt.digestSha256(), 901))
        assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.EXITED_UNCONFIRMED), futureExit.controller().acknowledgeTerminal(futureReady.handle))
        val preReady = Fixture().apply { readyVerificationTime = 600L }; val ready = preReady.controller().armToReady(preReady.signed, preReady.expected) as GuardianArmAdmission.Ready
        preReady.time = 900; val stock = preReady.stock(599, 674, 700)
        preReady.terminalLoad = GuardianTerminalLoad.Received(stock, preReady.exited(stock.receipt.digestSha256(), 750))
        assertEquals(GuardianTerminalAcknowledgement.Rejected(GuardianTerminalRejectReason.INVALID_STOCK), preReady.controller().acknowledgeTerminal(ready.handle))
    }

    @Test fun `post hard deadline stock can clear terminal deadline saturates and boot mismatch retains`() {
        val late = Fixture(); val lateReady = late.controller().armToReady(late.signed, late.expected) as GuardianArmAdmission.Ready
        late.time = 12_000; val stock = late.stock(10_500, 10_575, 10_600)
        late.terminalLoad = GuardianTerminalLoad.Received(stock, late.exited(stock.receipt.digestSha256(), 10_650))
        assertEquals(GuardianTerminalAcknowledgement.Cleared, late.controller().acknowledgeTerminal(lateReady.handle))

        val nearMax = Fixture(now = Long.MAX_VALUE - 2_000).apply { envelopeOverrideDeadline = Long.MAX_VALUE }
        val nearMaxReady = nearMax.controller().armToReady(nearMax.signed, nearMax.expected) as GuardianArmAdmission.Ready
        nearMax.terminalLoad = GuardianTerminalLoad.Unavailable
        assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE), nearMax.controller().acknowledgeTerminal(nearMaxReady.handle))
        assertEquals(listOf(Long.MAX_VALUE), nearMax.terminalDeadlines)

        val boot = Fixture(); val bootReady = boot.controller().armToReady(boot.signed, boot.expected) as GuardianArmAdmission.Ready
        boot.liveBoot = "other"; assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.BOOT_MISMATCH), boot.controller().acknowledgeTerminal(bootReady.handle))
    }

    @Test fun `clear false or throw retains terminal truth blocks rearm and exact retry clears`() {
        listOf(false, true).forEach { throws ->
            val fixture = Fixture(); val ready = fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.Ready
            fixture.time = 900; val stock = fixture.stock(500, 575, 600)
            fixture.terminalLoad = GuardianTerminalLoad.Received(stock, fixture.exited(stock.receipt.digestSha256(), 650))
            fixture.clearResult = false; fixture.clearThrows = throws
            assertEquals(GuardianTerminalAcknowledgement.Retained(GuardianTerminalRetainedReason.CLEAR_UNCONFIRMED), fixture.controller().acknowledgeTerminal(ready.handle))
            assertEquals(GuardianArmRejectReason.CONCURRENT_ARM, (fixture.controller().armToReady(fixture.signed, fixture.expected) as GuardianArmAdmission.ArmRejected).reason)
            fixture.clearThrows = false; fixture.clearResult = null
            assertEquals(GuardianTerminalAcknowledgement.Cleared, fixture.controller().acknowledgeTerminal(ready.handle))
        }
    }

    private inner class Fixture(now: Long = 500L) {
        var envelope = envelope()
            private set
        var envelopeOverrideDeadline: Long
            get() = envelope.hardDeadlineElapsedRealtimeMs
            set(value) {
                envelope = envelope.copy(hardDeadlineElapsedRealtimeMs = value)
                signed = RestoreGuardianCrypto.signEnvelope(envelope, signer)
                expected = expected(envelope)
                ready = RestoreGuardianCrypto.signReadyReceipt(receipt(envelope, time), sessionSigner)
            }
        var signed = RestoreGuardianCrypto.signEnvelope(envelope, signer)
        var expected = expected(envelope)
        private val memoryStore = InMemoryRestoreGuardianArmStore(available)
        var clearResult: Boolean? = null
        var clearThrows = false
        var pinThrows = false
        var pinOverride: GuardianSessionPinWrite? = null
        var reloadOverride: GuardianArmStoreLoad.LoadedReady? = null
        var unreadableAfterPin = false
        var reloadThrowsAfterPin = false
        var pinnedOnce = false
        val store: RestoreGuardianArmStore = object : RestoreGuardianArmStore {
            override fun arm(signed: SignedRestoreGuardianEnvelope) = memoryStore.arm(signed)
            override fun presence(): GuardianArmStorePresence = memoryStore.presence()
            override fun load(expected: RestoreGuardianExpectedIdentity) =
                if (pinnedOnce) when {
                    reloadThrowsAfterPin -> error("post-pin load")
                    unreadableAfterPin -> GuardianArmStoreLoad.Corrupt
                    else -> reloadOverride ?: memoryStore.load(expected)
                } else memoryStore.load(expected)
            override fun pinSessionKey(expected: RestoreGuardianExpectedIdentity, sessionKey: GuardianSessionPublicKey, signedReady: SignedRestoreGuardianReadyReceipt, nowElapsedRealtimeMs: Long): GuardianSessionPinWrite {
                if (pinThrows) error("pin")
                pinOverride?.let { return it }
                return memoryStore.pinSessionKey(expected, sessionKey, signedReady, nowElapsedRealtimeMs).also { pinnedOnce = it is GuardianSessionPinWrite.Pinned }
            }
            override fun clear(): Boolean { if (clearThrows) error("clear"); return clearResult ?: memoryStore.clear() }
        }
        var liveBoot = "boot.1"
        var time = now
        var apk = InstalledTweakerApkSource("/data/app/~~token/com.gruning.mimir.tweaker-x/base.apk", certificate)
        var stageResult = GuardianDispatchResult.CONFIRMED
        var watchResult = GuardianDispatchResult.CONFIRMED
        var sourceThrows = false
        var stageThrows = false
        var readyThrows = false
        val stageDetached = mutableListOf<Boolean>()
        val watcherDetached = mutableListOf<Boolean>()
        var readyCalls = 0
        var ready = RestoreGuardianCrypto.signReadyReceipt(receipt(envelope, now), sessionSigner)
        val channelEvidence = GuardianReadyChannelEvidence(0, 0, "600", "u:object_r:shell_data_file:s0", true)
        var channelExpectation = GuardianReadyChannelExpectation(0, 0, "600", "u:object_r:shell_data_file:s0", true, emptySet())
        var readyLoad: GuardianReadyLoad? = null
        var readyVerificationTime: Long? = null
        val readyDeadlines = mutableListOf<Long>()
        var heartbeatResult = GuardianDispatchResult.CONFIRMED
        var heartbeatThrows = false
        val dispatchedHeartbeatCounters = mutableListOf<Long>()
        var terminalLoad: GuardianTerminalLoad = GuardianTerminalLoad.Unavailable
        var terminalThrows = false
        val terminalDeadlines = mutableListOf<Long>()

        private val controllerInstance by lazy { RestoreGuardianController(
            armStore = store,
            pinnedVerifier = pinned,
            apkAuthority = PinnedTweakerApkAuthority(certificate),
            liveBootIdentity = { liveBoot },
            elapsedRealtimeMs = { time },
            installedApk = GuardianInstalledApkPort { if (sourceThrows) error("source") else apk },
            stageDispatcher = GuardianSynchronousDispatcher { invocation -> stageDetached += invocation.detached; if (stageThrows) error("stage") else stageResult },
            guardianDispatcher = GuardianDetachedDispatcher { invocation -> watcherDetached += invocation.detached; watchResult },
            readyReceipts = GuardianReadyReceiptPort { deadline -> readyCalls++; readyDeadlines += deadline; if (readyThrows) error("ready") else (readyLoad ?: GuardianReadyLoad.Received(ready, channelEvidence)).also { readyVerificationTime?.let { time = it } } },
            readyChannelExpectation = channelExpectation,
            heartbeatDispatcher = GuardianHeartbeatDispatchPort { signed ->
                dispatchedHeartbeatCounters += signed.heartbeat.counter
                if (heartbeatThrows) error("heartbeat") else heartbeatResult
            },
            terminalReceipts = GuardianTerminalReceiptPort { deadline -> terminalDeadlines += deadline; if (terminalThrows) error("terminal") else terminalLoad },
        ) }

        fun controller() = controllerInstance

        fun heartbeat(counter: Long, elapsed: Long, deadline: Long = envelope.hardDeadlineElapsedRealtimeMs) =
            RestoreGuardianCrypto.signHeartbeat(heartbeatPayload(counter, elapsed, deadline), signer)

        fun heartbeatPayload(counter: Long, elapsed: Long, deadline: Long = envelope.hardDeadlineElapsedRealtimeMs) = RestoreGuardianHeartbeat(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            counter = counter,
            elapsedRealtimeMs = elapsed,
            hardDeadlineElapsedRealtimeMs = deadline,
        )

        fun publish(handle: GuardianReadyHandle, signed: SignedRestoreGuardianHeartbeat): GuardianHeartbeatPublication =
            controller().publishHeartbeat(handle, signed)

        fun stock(first: Long, second: Long, issued: Long) = RestoreGuardianCrypto.signStockConfirmedReceipt(
            RestoreGuardianStockConfirmedReceipt(
                helperBuildIdentity = envelope.helperBuildIdentity, bootIdentity = envelope.bootIdentity, generation = envelope.generation,
                requestId = envelope.requestId, transactionId = envelope.transactionId, taskNonce = envelope.taskNonce,
                envelopeSha256 = envelope.digestSha256(), sessionKeySha256 = sessionKey.sha256(),
                verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(envelope.restoreEntries), firstReadbackElapsedRealtimeMs = first,
                secondReadbackElapsedRealtimeMs = second, issuedElapsedRealtimeMs = issued,
            ), sessionSigner,
        )
        fun exited(stockDigest: String, issued: Long) = RestoreGuardianCrypto.signExitedReceipt(
            RestoreGuardianExitedReceipt(
                helperBuildIdentity = envelope.helperBuildIdentity, bootIdentity = envelope.bootIdentity, generation = envelope.generation,
                requestId = envelope.requestId, transactionId = envelope.transactionId, taskNonce = envelope.taskNonce,
                envelopeSha256 = envelope.digestSha256(), sessionKeySha256 = sessionKey.sha256(),
                verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(envelope.restoreEntries), stockConfirmedReceiptSha256 = stockDigest, issuedElapsedRealtimeMs = issued,
            ), sessionSigner,
        )
    }

    private fun envelope() = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1", tweakerCertificateSha256 = certificate, hardwareFingerprint = "hardware.1", deviceProfile = "rp5.1",
        bootIdentity = "boot.1", generation = 7, requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32),
        topologySha256 = digest("topology"), snapshotSha256 = digest("snapshot"), planSha256 = digest("plan"), hardDeadlineElapsedRealtimeMs = 10_000,
        restoreEntries = listOf(RestoreGuardianFanPutEntry(1, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = listOf(1))),
    )

    private fun expected(value: RestoreGuardianEnvelope) = RestoreGuardianExpectedIdentity(
        value.helperBuildIdentity, value.tweakerCertificateSha256, value.hardwareFingerprint, value.deviceProfile, value.bootIdentity, value.generation,
        value.requestId, value.transactionId, value.taskNonce, value.topologySha256, value.snapshotSha256, value.hardDeadlineElapsedRealtimeMs,
    )

    private fun receipt(value: RestoreGuardianEnvelope, now: Long) = RestoreGuardianReadyReceipt(
        helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity, generation = value.generation, requestId = value.requestId,
        transactionId = value.transactionId, taskNonce = value.taskNonce, envelopeSha256 = value.digestSha256(), sessionPublicKey = sessionKey, issuedElapsedRealtimeMs = now,
    )

    private fun digest(value: String) = GuardianCanonicalCodec.sha256(value.toByteArray())

    private class FakeEcPrivateKey(private val parameters: ECParameterSpec) : ECPrivateKey {
        override fun getAlgorithm() = "EC"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
        override fun getParams() = parameters
        override fun getS() = java.math.BigInteger.ONE
    }

    private class FakeEcPublicKey(private val parameters: ECParameterSpec) : ECPublicKey {
        override fun getAlgorithm() = "EC"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
        override fun getParams() = parameters
        override fun getW() = parameters.generator
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}
