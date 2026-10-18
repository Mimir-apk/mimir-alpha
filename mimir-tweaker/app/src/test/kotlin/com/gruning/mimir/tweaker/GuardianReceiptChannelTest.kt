package com.gruning.mimir.tweaker

import java.util.ArrayDeque
import java.util.Base64
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianReceiptChannelTest {
    private val nonce = "nonce_12345678"
    private val topology = GuardianReceiptChannelTopology(nonce)
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public,
    )

    @Test fun `topology is nonce derived strict lowercase and never accepts path injection`() {
        val a = GuardianReceiptChannelTopology(nonce)
        val b = GuardianReceiptChannelTopology(nonce)
        assertEquals(a.directory, b.directory)
        assertEquals(a.markerNonce, b.markerNonce)
        assertTrue(a.sessionDirectory.matches(Regex("[0-9a-f]{16}")))
        assertTrue(a.record(GuardianReceiptChannelRecord.READY).endsWith("/R"))
        listOf("../attack", "nonce;rm", "short", "x".repeat(129)).forEach { assertFails { GuardianReceiptChannelTopology(it) } }
    }

    @Test fun `post unlock topology and ready expectation are frozen to the Probe 41R values`() {
        val policy = GuardianReceiptChannelPostUnlockTopology
        assertEquals("/data/local/tmp/mimir-rct1", policy.ROOT)
        assertEquals(0, policy.OWNER_UID)
        assertEquals(0, policy.OWNER_GID)
        assertEquals("700", policy.DIRECTORY_MODE_OCTAL)
        assertEquals("600", policy.RECORD_MODE_OCTAL)
        assertEquals("u:object_r:shell_data_file:s0", policy.RECORD_SELINUX_LABEL)
        assertEquals(128, policy.RECEIPT_CHUNK_BYTES)
        assertEquals(policy.ROOT, topology.directory.substringBeforeLast('/'))

        val expected = policy.READY_EXPECTATION
        assertEquals(0, expected.ownerUid)
        assertEquals(0, expected.ownerGid)
        assertEquals("600", expected.modeOctal)
        assertEquals("u:object_r:shell_data_file:s0", expected.seLinuxLabel)
        assertTrue(expected.exactBytesConfirmed)
        assertEquals(setOf(2000, 1000), expected.forbiddenWriterUids)
    }

    @Test fun `frozen ready expectation does not expose mutable forbidden writer uids`() {
        val expected = GuardianReceiptChannelPostUnlockTopology.READY_EXPECTATION
        @Suppress("UNCHECKED_CAST")
        val mutable = expected.forbiddenWriterUids as? MutableSet<Int>
        if (mutable != null) assertFails { mutable += 42 }
        assertEquals(setOf(2000, 1000), expected.forbiddenWriterUids)
        assertTrue(expected.exactBytesConfirmed)
    }

    @Test fun `all fixed lifecycle statements use bounded one line checked framing`() {
        val statements = GuardianReceiptChannelRecord.entries.flatMap { record ->
            listOf(
                GuardianReceiptChannelRenderer.stat(topology, record),
                GuardianReceiptChannelRenderer.digest(topology, record),
                GuardianReceiptChannelRenderer.chunk(topology, record, 0),
                GuardianReceiptChannelRenderer.publication(topology, record),
            )
        } + lifecycleStatements(GuardianReceiptChannelLifecycle.preArmSweep(topology)) +
            lifecycleStatements(GuardianReceiptChannelLifecycle.cleanup(topology))
        val marker = GuardianReceiptChannelParser.marker(topology.markerNonce)
        assertTrue(statements.all { it != null && it.toByteArray().size <= GuardianReceiptChannelRenderer.MAX_WRAPPED_STATEMENT_BYTES })
        assertTrue(statements.filterNotNull().all { it.contains(marker) && !it.contains("\n") && !it.contains("rm -rf") })
        assertTrue(statements.filterNotNull().all { GuardianReceiptChannelParser.acknowledgement(checked(""), topology.markerNonce) })
        listOf(
            "$marker:1:0:", " $marker:0:0:", "$marker:0:0: ", "\t$marker:0:0:", "$marker:0:0:\n",
            "\u0000$marker:0:0:", "$marker:0:0:\u0000", "$marker:0:0:$marker:0:0:",
        ).forEach { contaminated ->
            assertFalse(GuardianReceiptChannelParser.acknowledgement(raw(contaminated), topology.markerNonce))
        }
    }

    @Test fun `checked parser requires marker at index zero exact nonce numeric exit and exact length`() {
        val stat = "100:200:600:u:object_r:shell_data_file:s0:12"
        val marker = GuardianReceiptChannelParser.marker(topology.markerNonce)
        assertNotNull(GuardianReceiptChannelParser.stat(checked(stat), topology.markerNonce))
        listOf(
            "x${checked(stat).text}", checked(stat, exit = 1).text!!, "$marker:x:${stat.length}:$stat",
            "$marker:0:x:$stat", "$marker:0:${stat.length - 1}:$stat", "$marker:0:${stat.length + 1}:$stat",
            "${GuardianReceiptChannelParser.marker("0".repeat(16))}:0:${stat.length}:$stat", "null", " $marker:0:${stat.length}:$stat",
        ).forEach { assertNull(GuardianReceiptChannelParser.stat(raw(it), topology.markerNonce)) }
        assertNotNull(GuardianReceiptChannelParser.stat(sourceRaw("${checked(stat).text}\n"), topology.markerNonce))
        assertNull(GuardianReceiptChannelParser.stat(sourceRaw(" ${checked(stat).text}\n"), topology.markerNonce))
    }

    @Test fun `chunk parser accepts strict canonical base64 and frame decoder remains exact`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(1, 2, 3)))
        val encoded = Base64.getEncoder().encodeToString(framed)
        val marker = GuardianReceiptChannelParser.marker(topology.markerNonce)
        assertTrue(GuardianReceiptChannelParser.frame(checked(encoded), topology.markerNonce)!!.contentEquals(framed))
        assertTrue(GuardianReceiptChannelParser.decodeFrame(framed)!!.contentEquals(byteArrayOf(1, 2, 3)))
        val malformedFrame = Base64.getEncoder().encodeToString(framed.copyOf(framed.size - 1))
        val wrongMagic = framed.copyOf().also { it[0] = 0 }
        val declaredLengthMismatch = framed.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) }
        listOf(
            checked(encoded, exit = 1).text!!, "x${checked(encoded).text}", "$marker:0:${encoded.length - 1}:$encoded",
            "${encoded.dropLast(1)}?",
            checked(Base64.getEncoder().encodeToString(ByteArray(GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES + 1))).text!!,
        ).forEach { assertNull(GuardianReceiptChannelParser.frame(raw(it), topology.markerNonce)) }
        assertNull(GuardianReceiptChannelParser.decodeFrame(malformedFrame.toByteArray()))
        assertNull(GuardianReceiptChannelParser.decodeFrame(wrongMagic))
        assertNull(GuardianReceiptChannelParser.decodeFrame(declaredLengthMismatch))
    }

    @Test fun `one line parser rejects control nonascii and embedded marker spoof`() {
        // Eight frame-header bytes plus one payload byte align 33 84 40 to canonical base64 M4RA.
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(0, 0x33, 0x84.toByte(), 0x40)))
        val encoded = Base64.getEncoder().encodeToString(framed)
        val marker = GuardianReceiptChannelParser.marker(topology.markerNonce)
        assertTrue(encoded.contains("M4R"))
        assertNotNull(GuardianReceiptChannelParser.frame(checked(encoded), topology.markerNonce))
        assertNull(GuardianReceiptChannelParser.stat(raw("$marker:0:1:\u0000"), topology.markerNonce))
        assertNull(GuardianReceiptChannelParser.frame(raw("$marker:0:1:é"), topology.markerNonce))
        assertNull(GuardianReceiptChannelParser.frame(raw("$marker:0:${encoded.length + marker.length}:$encoded$marker"), topology.markerNonce))
        val absent = GuardianReceiptChannelRawReply.fromPServerUtf8(null)
        assertNull(absent.text)
        assertFalse(absent.trimAmbiguity)
    }

    @Test fun `raw PServer executor calls once and maps transport plus rejected transact`() {
        var calls = 0
        var observedStatement: String? = null
        val failed = GuardianReceiptChannelPServerExecutor {
            calls += 1
            observedStatement = it
            Result.failure(IllegalStateException("binder unavailable"))
        }
        val failedResult = failed.execute("opaque statement")
        assertEquals(1, calls)
        assertEquals("opaque statement", observedStatement)
        assertTrue(failedResult is GuardianReceiptChannelExecution.Transport)
        assertEquals("binder unavailable", (failedResult as GuardianReceiptChannelExecution.Transport).detail)

        calls = 0
        val rejected = GuardianReceiptChannelPServerExecutor {
            calls += 1
            Result.success(PServerRawReply(accepted = false, bytes = byteArrayOf(1)))
        }
        val rejectedResult = rejected.execute("opaque statement")
        assertEquals(1, calls)
        assertEquals(
            "transact rejected",
            (rejectedResult as GuardianReceiptChannelExecution.Transport).detail,
        )
    }

    @Test fun `raw PServer executor maps a throwing raw boundary to transport without replay`() {
        var calls = 0
        var observedStatement: String? = null
        val result = GuardianReceiptChannelPServerExecutor {
            calls += 1
            observedStatement = it
            throw IllegalArgumentException("raw boundary threw")
        }.execute("opaque statement")
        assertEquals(1, calls)
        assertEquals("opaque statement", observedStatement)
        assertEquals(
            "raw boundary threw",
            (result as GuardianReceiptChannelExecution.Transport).detail,
        )
    }

    @Test fun `raw PServer executor distinguishes accepted absent bytes from accepted empty bytes`() {
        val absent = GuardianReceiptChannelPServerExecutor {
            Result.success(PServerRawReply(accepted = true, bytes = null))
        }.execute("opaque statement")
        assertTrue(absent === GuardianReceiptChannelExecution.Absent)

        val empty = GuardianReceiptChannelPServerExecutor {
            Result.success(PServerRawReply(accepted = true, bytes = ByteArray(0)))
        }.execute("opaque statement")
        assertTrue(empty is GuardianReceiptChannelExecution.Reply)
        val emptyReply = empty as GuardianReceiptChannelExecution.Reply
        assertEquals("", emptyReply.raw.text)
        assertFalse(emptyReply.raw.trimAmbiguity)
    }

    @Test fun `raw PServer executor preserves accepted reply bytes through the raw UTF8 boundary`() {
        val result = GuardianReceiptChannelPServerExecutor {
            Result.success(PServerRawReply(accepted = true, bytes = "ready\n".toByteArray(Charsets.UTF_8)))
        }.execute("opaque statement")
        assertTrue(result is GuardianReceiptChannelExecution.Reply)
        val reply = result as GuardianReceiptChannelExecution.Reply
        assertEquals("ready", reply.raw.text)
        assertFalse(reply.raw.trimAmbiguity) // the raw boundary accepts exactly one terminal LF.
    }

    @Test fun `port requires exact double stat digest chunks and frame stability`() {
        val payload = byteArrayOf(9, 8, 7)
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(payload))
        val events = stableEvents(framed)
        val result = port(events).loadUntil(10)
        assertEquals(GuardianReadyLoad.Ambiguous, result) // decoder is intentionally absent, but stable channel reached it.
        assertTrue(events.isEmpty())
    }

    @Test fun `port rejects TOCTOU metadata bytes marker and transport ambiguity`() {
        val first = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(1)))
        val changed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(2)))
        val changedBytes = ArrayDeque<GuardianReceiptChannelExecution>(sampleEvents(first) + sampleEvents(changed))
        assertEquals(GuardianReadyLoad.Ambiguous, port(changedBytes).loadUntil(10))
        val badMarker = ArrayDeque<GuardianReceiptChannelExecution>(listOf(
            GuardianReceiptChannelExecution.Reply(checked("100:200:600:label:${first.size}", exit = 1)),
        ))
        assertEquals(GuardianReadyLoad.Ambiguous, port(badMarker).loadUntil(10))
        assertEquals(GuardianReadyLoad.Unavailable, port(ArrayDeque(listOf(GuardianReceiptChannelExecution.Absent))).loadUntil(10))
        assertEquals(GuardianReadyLoad.Ambiguous, port(ArrayDeque(listOf(GuardianReceiptChannelExecution.Transport("nope")))).loadUntil(10))
        assertEquals(GuardianReadyLoad.TimedOut, port(ArrayDeque(), now = 11).loadUntil(10))
    }

    @Test fun `port refuses a receipt when the deadline crosses after the final read`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(4)))
        val events = stableEvents(framed)
        val times = ArrayDeque(listOf(0L, 0L, 0L, 0L, 0L, 0L, 11L))
        val port = GuardianReceiptChannelReadyPort(
            topology = topology,
            executor = GuardianReceiptChannelExecutor { events.removeFirst() },
            expected = GuardianReadyChannelExpectation(100, 200, "600", "label", true, emptySet()),
            runtimeAppUid = 999,
            decode = { null },
            nowElapsedRealtimeMs = { if (times.isEmpty()) 11L else times.removeFirst() },
        )
        assertEquals(GuardianReadyLoad.TimedOut, port.loadUntil(10))
        assertFalse(events.isEmpty()) // expiry stops before the remaining indexed sample can execute
    }

    @Test fun `forbidden or app uid never gains channel evidence`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(3)))
        val events = stableEvents(framed)
        val expectation = GuardianReadyChannelExpectation(100, 200, "600", "label", true, setOf(100))
        var decoded = false
        val port = GuardianReceiptChannelReadyPort(topology, GuardianReceiptChannelExecutor { events.removeFirst() }, expectation, runtimeAppUid = 101, decode = { decoded = true; signedReady() }, nowElapsedRealtimeMs = { 0 })
        assertEquals(GuardianReadyLoad.Ambiguous, port.loadUntil(10))
        assertFalse(decoded)
    }

    @Test fun `port reaches received only for exact matching metadata with a decoded receipt`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(5)))
        val events = stableEvents(framed)
        val result = readyPort(events, GuardianReadyChannelExpectation(100, 200, "600", "label", true, emptySet()))
        assertTrue(result.loadUntil(10) is GuardianReadyLoad.Received)
    }

    @Test fun `port rejects channel policy mismatch and stat byte count mismatch before grant`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(byteArrayOf(6)))
        assertEquals(
            GuardianReadyLoad.Ambiguous,
            readyPort(stableEvents(framed), GuardianReadyChannelExpectation(100, 201, "600", "label", true, emptySet())).loadUntil(10),
        )
        val wrongCount = ArrayDeque<GuardianReceiptChannelExecution>(sampleEvents(framed, declaredBytes = framed.size - 1) + sampleEvents(framed, declaredBytes = framed.size - 1))
        assertEquals(
            GuardianReadyLoad.Ambiguous,
            readyPort(wrongCount, GuardianReadyChannelExpectation(100, 200, "600", "label", true, emptySet())).loadUntil(10),
        )
    }

    @Test fun `port rejects digest mismatch invalid or missing indexed chunks`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(ByteArray(600) { it.toByte() }))
        val digestMismatch = ArrayDeque<GuardianReceiptChannelExecution>(
            listOf(statReply(framed.size), GuardianReceiptChannelExecution.Reply(checked("0".repeat(64)))) +
            framed.asList().chunked(GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES)
                    .map { values -> chunkReply(values.toByteArray()) },
        )
        assertEquals(GuardianReadyLoad.Ambiguous, port(digestMismatch).loadUntil(10))

        val invalidChunk = ArrayDeque<GuardianReceiptChannelExecution>(listOf(
            statReply(framed.size), digestReply(framed), GuardianReceiptChannelExecution.Reply(checked("")),
        ))
        assertEquals(GuardianReadyLoad.Ambiguous, port(invalidChunk).loadUntil(10))

        val missingChunk = ArrayDeque<GuardianReceiptChannelExecution>(listOf(
            statReply(framed.size), digestReply(framed),
            chunkReply(framed.copyOfRange(0, GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES)),
            GuardianReceiptChannelExecution.Absent,
        ))
        assertEquals(GuardianReadyLoad.Unavailable, port(missingChunk).loadUntil(10))
    }

    @Test fun `device proven 128 byte chunks render and reassemble deterministically`() {
        val framed = requireNotNull(GuardianReceiptChannelParser.encodeFrame(ByteArray(248) { it.toByte() }))
        assertEquals(256, framed.size)
        assertEquals(128, GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES)
        assertTrue(GuardianReceiptChannelRenderer.chunk(topology, GuardianReceiptChannelRecord.READY, 1)?.contains("bs=128") == true)

        val events = stableEvents(framed)
        assertTrue(
            readyPort(events, GuardianReadyChannelExpectation(100, 200, "600", "label", true, emptySet()))
                .loadUntil(10) is GuardianReadyLoad.Received,
        )
        assertTrue(events.isEmpty())
    }

    @Test fun `renderer retains the 198 byte budget and never emits a suffix wrapper`() {
        val statements = GuardianReceiptChannelRecord.entries.flatMap { record -> listOfNotNull(
            GuardianReceiptChannelRenderer.stat(topology, record),
            GuardianReceiptChannelRenderer.digest(topology, record),
            GuardianReceiptChannelRenderer.chunk(topology, record, 15),
            GuardianReceiptChannelRenderer.publication(topology, record),
        ) }
        assertEquals(198, GuardianReceiptChannelRenderer.MAX_WRAPPED_STATEMENT_BYTES)
        assertTrue(statements.maxOf { it.toByteArray().size } <= 198)
        assertTrue(statements.all { it.startsWith("b=\$(") && it.contains("\"\${#b}\"") && !it.contains("\\nM4R") })
    }

    @Test fun `overlong rendered command is withheld instead of becoming an executable fallback`() {
        assertNull(GuardianReceiptChannelRenderer.chunk(topology, GuardianReceiptChannelRecord.READY, Int.MAX_VALUE))
        assertTrue(GuardianReceiptChannelLifecycle.preArmSweep(topology) is GuardianReceiptChannelLifecyclePlan.Statements)
        assertTrue(GuardianReceiptChannelLifecycle.publish(topology, GuardianReceiptChannelRecord.READY) is GuardianReceiptChannelLifecyclePlan.Statements)
        assertTrue(GuardianReceiptChannelLifecycle.cleanup(topology) is GuardianReceiptChannelLifecyclePlan.Statements)
    }

    @Test fun `lifecycle reducer remains fixed nonrecursive and factory stays frozen`() {
        val planned = lifecycleStatements(GuardianReceiptChannelLifecycle.preArmSweep(topology)) +
            lifecycleStatements(GuardianReceiptChannelLifecycle.cleanup(topology))
        assertEquals(13, planned.size)
        assertTrue(planned.take(12).all { it.contains("rm -f '") && !it.contains(" -r") && !it.contains("*") })
        assertTrue(planned.last().contains("rmdir '") && !planned.last().contains(" -r"))
        assertTrue(lifecycleStatements(GuardianReceiptChannelLifecycle.publish(topology, GuardianReceiptChannelRecord.READY)).single().contains("/.R'"))
        assertFails { GuardianReceiptChannelProductionFreeze.unavailable() }
        assertEquals(GuardianReceiptChannelLifecycleResult.SweepRequired(setOf("R", ".T")), GuardianReceiptChannelLifecycle.reduce(setOf("R", ".T"), GuardianReceiptChannelLifecycleEvent.PreArmSweep))
        assertEquals(GuardianReceiptChannelLifecycleResult.ForeignResidue, GuardianReceiptChannelLifecycle.reduce(setOf("foreign", "R"), GuardianReceiptChannelLifecycleEvent.Cleanup))
    }

    @Test fun `root attestation creates once then requires a fresh exact lstat`() {
        val absent = candidateRoot(GuardianReceiptChannelNodeKind.ABSENT)
        val create = GuardianReceiptChannelRootAttestationReducer.reduce(GuardianReceiptChannelRootAttestationState.Initial, absent)
        assertEquals(GuardianReceiptChannelRootAttestationState.AwaitingCreateLstat, create.state)
        assertEquals(GuardianReceiptChannelRootAttestationDecision.CreateWithoutParents, create.decision)
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(create.state, absent),
            GuardianReceiptChannelRootStopReason.CreateDidNotMaterialize,
        )
        val ready = GuardianReceiptChannelRootAttestationReducer.reduce(create.state, exactCandidateRoot())
        assertEquals(GuardianReceiptChannelRootAttestationDecision.CandidateRootReady, ready.decision)
    }

    @Test fun `root attestation recovers exactly one wrong-owned empty real directory`() {
        val wrongOwnedEmpty = candidateRoot(GuardianReceiptChannelNodeKind.DIRECTORY, uid = 2000)
        val recovery = GuardianReceiptChannelRootAttestationReducer.reduce(GuardianReceiptChannelRootAttestationState.Initial, wrongOwnedEmpty)
        assertEquals(GuardianReceiptChannelRootAttestationState.AwaitingRecoveryLstat, recovery.state)
        assertEquals(GuardianReceiptChannelRootAttestationDecision.RecoverWrongOwnedEmpty, recovery.decision)
        assertEquals(
            GuardianReceiptChannelRootAttestationDecision.CandidateRootReady,
            GuardianReceiptChannelRootAttestationReducer.reduce(recovery.state, exactCandidateRoot()).decision,
        )
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(recovery.state, wrongOwnedEmpty),
            GuardianReceiptChannelRootStopReason.ResquatAfterRecovery,
        )
    }

    @Test fun `candidate root permits only its exact derived session directory not records`() {
        val session = topology.sessionDirectory
        assertEquals(
            GuardianReceiptChannelRootAttestationDecision.CandidateRootReady,
            GuardianReceiptChannelRootAttestationReducer.reduce(
                GuardianReceiptChannelRootAttestationState.Initial,
                exactCandidateRoot(setOf(session)),
            ).decision,
        )
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(
                GuardianReceiptChannelRootAttestationState.Initial,
                exactCandidateRoot(setOf("R")),
            ),
            GuardianReceiptChannelRootStopReason.ForeignChild,
        )
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(
                GuardianReceiptChannelRootAttestationState.Initial,
                exactCandidateRoot(setOf("0".repeat(16))),
            ),
            GuardianReceiptChannelRootStopReason.ForeignChild,
        )
    }

    @Test fun `session directory permits fixed records but never a sibling session`() {
        assertEquals(
            GuardianReceiptChannelSessionAttestationDecision.SessionDirectoryReady,
            GuardianReceiptChannelRootAttestationReducer.reduceSession(
                GuardianReceiptChannelRootAttestationState.Initial,
                exactSession(setOf("R", "T", "X", ".R", ".T", ".X")),
            ).decision,
        )
        assertSessionStop(
            GuardianReceiptChannelRootAttestationReducer.reduceSession(
                GuardianReceiptChannelRootAttestationState.Initial,
                exactSession(setOf("0".repeat(16))),
            ),
            GuardianReceiptChannelRootStopReason.ForeignChild,
        )
    }

    @Test fun `session directory preserves one-shot create and empty wrong-owner recovery`() {
        val create = GuardianReceiptChannelRootAttestationReducer.reduceSession(
            GuardianReceiptChannelRootAttestationState.Initial,
            session(GuardianReceiptChannelNodeKind.ABSENT),
        )
        assertEquals(GuardianReceiptChannelSessionAttestationDecision.CreateWithoutParents, create.decision)
        assertSessionStop(
            GuardianReceiptChannelRootAttestationReducer.reduceSession(create.state, session(GuardianReceiptChannelNodeKind.ABSENT)),
            GuardianReceiptChannelRootStopReason.CreateDidNotMaterialize,
        )
        val recovery = GuardianReceiptChannelRootAttestationReducer.reduceSession(
            GuardianReceiptChannelRootAttestationState.Initial,
            session(GuardianReceiptChannelNodeKind.DIRECTORY, uid = 2000),
        )
        assertEquals(GuardianReceiptChannelSessionAttestationDecision.RecoverWrongOwnedEmpty, recovery.decision)
        assertSessionStop(
            GuardianReceiptChannelRootAttestationReducer.reduceSession(
                recovery.state,
                session(GuardianReceiptChannelNodeKind.DIRECTORY, uid = 2000),
            ),
            GuardianReceiptChannelRootStopReason.ResquatAfterRecovery,
        )
    }

    @Test fun `arm readiness requires separate exact candidate root and session lstat`() {
        val candidate = GuardianReceiptChannelRootAttestationReducer.reduce(
            GuardianReceiptChannelRootAttestationState.Initial,
            exactCandidateRoot(setOf(topology.sessionDirectory)),
        )
        val session = GuardianReceiptChannelRootAttestationReducer.reduceSession(
            GuardianReceiptChannelRootAttestationState.Initial,
            exactSession(setOf("R")),
        )
        assertTrue(GuardianReceiptChannelArmReadiness.isReady(candidate, session))
        val missingSession = GuardianReceiptChannelRootAttestationReducer.reduceSession(
            GuardianReceiptChannelRootAttestationState.Initial,
            session(GuardianReceiptChannelNodeKind.ABSENT),
        )
        assertFalse(GuardianReceiptChannelArmReadiness.isReady(candidate, missingSession))
    }

    @Test fun `root attestation fails closed on unsafe node ownership and metadata`() {
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(GuardianReceiptChannelRootAttestationState.Initial, candidateRoot(GuardianReceiptChannelNodeKind.SYMLINK)),
            GuardianReceiptChannelRootStopReason.Symlink,
        )
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(GuardianReceiptChannelRootAttestationState.Initial, candidateRoot(GuardianReceiptChannelNodeKind.OTHER)),
            GuardianReceiptChannelRootStopReason.NonDirectory,
        )
        assertStop(
            GuardianReceiptChannelRootAttestationReducer.reduce(
                GuardianReceiptChannelRootAttestationState.Initial,
                exactCandidateRoot(setOf(topology.sessionDirectory), uid = 2000),
            ),
            GuardianReceiptChannelRootStopReason.WrongOwnedNonEmpty,
        )
        listOf(
            candidateRoot(GuardianReceiptChannelNodeKind.DIRECTORY, mode = "755"),
            candidateRoot(GuardianReceiptChannelNodeKind.DIRECTORY, gid = 2000),
            candidateRoot(GuardianReceiptChannelNodeKind.DIRECTORY, label = ""),
            candidateRoot(GuardianReceiptChannelNodeKind.DIRECTORY, label = "u:object r:bad"),
        ).forEach { ambiguous ->
            assertStop(
                GuardianReceiptChannelRootAttestationReducer.reduce(GuardianReceiptChannelRootAttestationState.Initial, ambiguous),
                GuardianReceiptChannelRootStopReason.MetadataAmbiguous,
            )
        }
    }

    private fun lstat(
        kind: GuardianReceiptChannelNodeKind,
        uid: Int? = 0,
        gid: Int? = 0,
        mode: String? = "700",
        label: String? = "u:object_r:root_attestation:s0",
    ) = GuardianReceiptChannelLstatObservation(kind, uid, gid, mode, label)

    private fun candidateRoot(
        kind: GuardianReceiptChannelNodeKind,
        children: Set<String> = emptySet(),
        uid: Int? = 0,
        gid: Int? = 0,
        mode: String? = "700",
        label: String? = "u:object_r:root_attestation:s0",
    ) = GuardianReceiptChannelCandidateRootObservation(lstat(kind, uid, gid, mode, label), topology.sessionDirectory, children)

    private fun exactCandidateRoot(children: Set<String> = emptySet(), uid: Int = 0) =
        candidateRoot(GuardianReceiptChannelNodeKind.DIRECTORY, children, uid)

    private fun session(
        kind: GuardianReceiptChannelNodeKind,
        children: Set<String> = emptySet(),
        uid: Int? = 0,
        gid: Int? = 0,
        mode: String? = "700",
        label: String? = "u:object_r:root_attestation:s0",
    ) = GuardianReceiptChannelSessionDirectoryObservation(lstat(kind, uid, gid, mode, label), children)

    private fun exactSession(children: Set<String> = emptySet(), uid: Int = 0) =
        session(GuardianReceiptChannelNodeKind.DIRECTORY, children, uid)

    private fun assertStop(
        result: GuardianReceiptChannelRootAttestationResult,
        reason: GuardianReceiptChannelRootStopReason,
    ) = assertEquals(GuardianReceiptChannelRootAttestationDecision.StopForOperator(reason), result.decision)

    private fun assertSessionStop(
        result: GuardianReceiptChannelSessionAttestationResult,
        reason: GuardianReceiptChannelRootStopReason,
    ) = assertEquals(GuardianReceiptChannelSessionAttestationDecision.StopForOperator(reason), result.decision)

    private fun statReply(frameBytes: Int): GuardianReceiptChannelExecution.Reply =
        GuardianReceiptChannelExecution.Reply(checked("100:200:600:label:$frameBytes"))

    private fun digestReply(frame: ByteArray): GuardianReceiptChannelExecution.Reply =
        GuardianReceiptChannelExecution.Reply(checked(sha256(frame)))

    private fun chunkReply(chunk: ByteArray): GuardianReceiptChannelExecution.Reply =
        GuardianReceiptChannelExecution.Reply(checked(Base64.getEncoder().encodeToString(chunk)))

    private fun sampleEvents(framed: ByteArray, declaredBytes: Int = framed.size): List<GuardianReceiptChannelExecution> {
        val chunks = framed.asList().chunked(GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES)
            .map { values -> values.toByteArray() }
        return listOf(statReply(declaredBytes), digestReply(framed)) + chunks.map(::chunkReply)
    }

    private fun stableEvents(framed: ByteArray) = ArrayDeque<GuardianReceiptChannelExecution>(sampleEvents(framed) + sampleEvents(framed))

    private fun readyPort(events: ArrayDeque<GuardianReceiptChannelExecution>, expected: GuardianReadyChannelExpectation) =
        GuardianReceiptChannelReadyPort(topology, GuardianReceiptChannelExecutor { events.removeFirst() }, expected, runtimeAppUid = 999, decode = { signedReady() }, nowElapsedRealtimeMs = { 0 })

    private fun signedReady() = SignedRestoreGuardianReadyReceipt(
        RestoreGuardianReadyReceipt(
            helperBuildIdentity = "helper.1", bootIdentity = "boot.1", generation = 1,
            requestId = "request.1", transactionId = "transaction.1", taskNonce = "a".repeat(32),
            envelopeSha256 = "a".repeat(64), sessionPublicKey = sessionKey, issuedElapsedRealtimeMs = 0,
        ),
        byteArrayOf(1),
    )

    /** This is the post-trim UTF-8 String PServerClient exposes, not a split byte channel. */
    private fun raw(text: String): GuardianReceiptChannelRawReply = GuardianReceiptChannelRawReply.fromPServerOutput(text)

    private fun checked(body: String, exit: Int = 0): GuardianReceiptChannelRawReply = raw(
        "${GuardianReceiptChannelParser.marker(topology.markerNonce)}:$exit:${body.length}:$body",
    )

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sourceRaw(text: String): GuardianReceiptChannelRawReply =
        GuardianReceiptChannelRawReply.fromPServerUtf8(text.toByteArray(Charsets.UTF_8))

    private fun port(events: ArrayDeque<GuardianReceiptChannelExecution>, now: Long = 0): GuardianReceiptChannelReadyPort =
        GuardianReceiptChannelReadyPort(
            topology, GuardianReceiptChannelExecutor { if (events.isEmpty()) GuardianReceiptChannelExecution.Absent else events.removeFirst() },
            GuardianReadyChannelExpectation(100, 200, "600", "label", true, emptySet()), runtimeAppUid = 999,
            decode = { null }, nowElapsedRealtimeMs = { now },
        )

    private fun assertFails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    private fun lifecycleStatements(plan: GuardianReceiptChannelLifecyclePlan): List<String> =
        (plan as? GuardianReceiptChannelLifecyclePlan.Statements)?.values ?: emptyList()
}
