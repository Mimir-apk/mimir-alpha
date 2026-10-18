package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Host contract for [GuardianTerminalLoadObservability] and for the five exits of
 * `GuardianTerminalReceiptStore.loadOnce()` that feed it.
 *
 * The defect this pins: `GuardianTransactionReconciler.ready()` reports
 * `settlement=READY_RETAIN:null:false` whenever `terminal !is GuardianTerminalLoad.Received`, and
 * three of the store's four `Ambiguous` exits are indistinguishable at that point. The load itself
 * is unchanged -- each test asserts the returned [GuardianTerminalLoad] *and* the cause, so a
 * regression that fixes the logging by changing the answer fails here.
 *
 * The record/staging file names are spelled out below because they are `private` to the store. If
 * they ever change, these tests fail loudly rather than silently exercising `record_absent` five
 * times -- which is the whole failure mode `differential-test-the-regression-fixture` warns about.
 *
 * Kotlin `object`s live for the whole test process, so every assertion here reads
 * [GuardianTerminalLoadObservability.lastRecorded] immediately after the call that set it, and the
 * dedup tests use detail values unique to this file so the first emit is always a real edge.
 */
class GuardianTerminalLoadObservabilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordName = "mimir_guardian_terminal_receipts.v1"
    private val stagingName = ".mimir_guardian_terminal_receipts.v1.tmp"

    private fun storeIn(name: String): Pair<GuardianTerminalReceiptStore, File> {
        val directory = temporaryFolder.newFolder(name).absoluteFile
        return GuardianTerminalReceiptStore(directory) to directory
    }

    /**
     * A genuinely chained, genuinely signed pair -- same construction
     * [RestoreGuardianProcessContractTest] uses for the codec. A hand-rolled byte array would
     * decode to `null` and quietly turn the negative control into a fifth `decode_null` test.
     */
    private fun chainedPair(): Pair<SignedRestoreGuardianStockConfirmedReceipt, SignedRestoreGuardianExitedReceipt> {
        val authority = checkNotNull(GuardianProcessSessionAuthority.generate())
        val session = authority.sessionPublicKey
        val stockReceipt = RestoreGuardianStockConfirmedReceipt(
            helperBuildIdentity = "helper-1",
            bootIdentity = "boot-1",
            generation = 1,
            requestId = "request-1",
            transactionId = "transaction-1",
            taskNonce = "a".repeat(32),
            envelopeSha256 = "b".repeat(64),
            sessionKeySha256 = session.sha256(),
            firstReadbackElapsedRealtimeMs = 1_000,
            secondReadbackElapsedRealtimeMs = 1_100,
            issuedElapsedRealtimeMs = 1_100,
        )
        val stock = SignedRestoreGuardianStockConfirmedReceipt(
            stockReceipt,
            checkNotNull(
                authority.authenticate(GuardianReceiptType.STOCK_CONFIRMED, stockReceipt.canonicalBytes()),
            ),
        )
        val exitedReceipt = RestoreGuardianExitedReceipt(
            helperBuildIdentity = "helper-1",
            bootIdentity = "boot-1",
            generation = 1,
            requestId = "request-1",
            transactionId = "transaction-1",
            taskNonce = "a".repeat(32),
            envelopeSha256 = "b".repeat(64),
            sessionKeySha256 = session.sha256(),
            stockConfirmedReceiptSha256 = stockReceipt.digestSha256(),
            issuedElapsedRealtimeMs = 1_101,
        )
        val exited = SignedRestoreGuardianExitedReceipt(
            exitedReceipt,
            checkNotNull(authority.authenticate(GuardianReceiptType.EXITED, exitedReceipt.canonicalBytes())),
        )
        return stock to exited
    }

    // --- The seam itself: rendering, and the dedup that makes it safe on eleven call sites. ---

    @Test
    fun `describe names the cause and omits an absent detail`() {
        assertEquals(
            "terminalReceipt load cause=decode_null detail=64",
            GuardianTerminalLoadObservability.describe("decode_null", "64"),
        )
        assertEquals(
            "terminalReceipt load cause=record_absent",
            GuardianTerminalLoadObservability.describe("record_absent", null),
        )
    }

    @Test
    fun `an unchanged line is recorded every time but emitted once`() {
        val detail = "dedup-probe-1"

        assertTrue(GuardianTerminalLoadObservability.onLoadResolved("threw", detail))
        assertEquals(
            "terminalReceipt load cause=threw detail=dedup-probe-1",
            GuardianTerminalLoadObservability.lastRecorded,
        )

        // Same line: suppressed, so a caller under a 2.5s retry cannot flood logcat...
        assertFalse(GuardianTerminalLoadObservability.onLoadResolved("threw", detail))
        // ...and lastRecorded still updated, so the suppression is not a blind spot.
        assertEquals(
            "terminalReceipt load cause=threw detail=dedup-probe-1",
            GuardianTerminalLoadObservability.lastRecorded,
        )

        // A changed detail is a real edge and must emit, even on the same cause.
        assertTrue(GuardianTerminalLoadObservability.onLoadResolved("threw", "dedup-probe-2"))
        // As is a changed cause.
        assertTrue(GuardianTerminalLoadObservability.onLoadResolved("decode_null", "dedup-probe-2"))
    }

    // --- The five exits, driven through the real store on real files. ---

    @Test
    fun `an absent record loads Unavailable and says record_absent`() {
        val (store, _) = storeIn("absent")

        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Unavailable)
        assertEquals(
            "terminalReceipt load cause=record_absent",
            GuardianTerminalLoadObservability.lastRecorded,
        )
    }

    @Test
    fun `a leftover staging file loads Ambiguous and says staging_present`() {
        val (store, directory) = storeIn("staging")
        // Deliberately WITH a valid-looking record beside it: staging wins, and that is the point
        // -- one interrupted write poisons every later read until persist() runs again.
        File(directory, recordName).writeBytes(ByteArray(64) { 7 })
        File(directory, stagingName).writeBytes(ByteArray(8))

        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Ambiguous)
        assertEquals(
            "terminalReceipt load cause=staging_present",
            GuardianTerminalLoadObservability.lastRecorded,
        )
    }

    @Test
    fun `an empty record loads Ambiguous and reports the observed length`() {
        val (store, directory) = storeIn("empty")
        File(directory, recordName).writeBytes(ByteArray(0))

        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Ambiguous)
        assertEquals(
            "terminalReceipt load cause=length_out_of_range detail=0",
            GuardianTerminalLoadObservability.lastRecorded,
        )
    }

    @Test
    fun `an oversized record loads Ambiguous and reports the observed length`() {
        val (store, directory) = storeIn("oversized")
        File(directory, recordName).writeBytes(ByteArray(8 * 1024 + 1))

        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Ambiguous)
        assertEquals(
            "terminalReceipt load cause=length_out_of_range detail=8193",
            GuardianTerminalLoadObservability.lastRecorded,
        )
    }

    @Test
    fun `an undecodable record loads Ambiguous and says decode_null`() {
        val (store, directory) = storeIn("undecodable")
        // In range, present, not a frame -- the codec's MAGIC check refuses it.
        File(directory, recordName).writeBytes(ByteArray(64) { it.toByte() })

        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Ambiguous)
        assertEquals(
            "terminalReceipt load cause=decode_null detail=64",
            GuardianTerminalLoadObservability.lastRecorded,
        )
    }

    @Test
    fun `a chained pair loads Received and says received -- the negative control`() {
        val (store, _) = storeIn("received")
        val (stock, exited) = chainedPair()

        assertTrue(store.persist(stock, exited))
        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Received)

        val line = checkNotNull(GuardianTerminalLoadObservability.lastRecorded)
        // This is the assertion that makes the instrument trustworthy: if the success path were
        // silent, a quiet logcat could mean "always Received" or "never read" and nothing tells
        // them apart. It carries the frame size, so a truncation shows up as a shrinking number.
        assertTrue(line, line.startsWith("terminalReceipt load cause=received detail="))
        assertTrue(line, line.removePrefix("terminalReceipt load cause=received detail=").toInt() > 0)
    }

    @Test
    fun `an unreadable record loads Ambiguous and says threw, not decode_null`() {
        val (store, directory) = storeIn("unreadable")
        val record = File(directory, recordName)
        record.writeBytes(ByteArray(64) { it.toByte() })
        record.setReadable(false, false)
        // Root can read anything, so on a root test runner this branch is unreachable and the
        // test skips rather than passing for the wrong reason. The seam-level proof that
        // CAUSE_THREW renders and emits is in the dedup test above, which does not need a file.
        assumeFalse("record still readable -- test runner is likely root", record.canRead())

        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Ambiguous)
        assertEquals(
            "terminalReceipt load cause=threw detail=FileNotFoundException",
            GuardianTerminalLoadObservability.lastRecorded,
        )

        record.setReadable(true, true)
    }
}
