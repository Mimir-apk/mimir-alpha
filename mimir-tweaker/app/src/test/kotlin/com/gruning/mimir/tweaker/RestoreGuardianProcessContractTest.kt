package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RestoreGuardianProcessContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `caller policy accepts only the exact nonnegative package uid`() {
        assertTrue(GuardianProcessCallerPolicy.allows(10_123, 10_123))
        assertFalse(GuardianProcessCallerPolicy.allows(10_124, 10_123))
        assertFalse(GuardianProcessCallerPolicy.allows(-1, -1))
    }

    @Test
    fun `process session key signs every receipt direction and retires closed`() {
        val authority = GuardianProcessSessionAuthority.generate()
        assertNotNull(authority)
        val active = checkNotNull(authority)
        val payload = byteArrayOf(1, 2, 3)

        GuardianReceiptType.entries.forEach { type ->
            val signature = active.authenticate(type, payload)
            assertNotNull(signature)
            assertTrue(active.sessionPublicKey.verifier().verify(payload, checkNotNull(signature)))
        }
        active.close()
        assertNull(active.authenticate(GuardianReceiptType.READY, payload))
    }

    @Test
    fun `terminal codec and atomic store require a chained pair`() {
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
            checkNotNull(authority.authenticate(GuardianReceiptType.STOCK_CONFIRMED, stockReceipt.canonicalBytes())),
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

        val encoded = checkNotNull(GuardianTerminalWireCodec.encode(stock, exited))
        val decoded = checkNotNull(GuardianTerminalWireCodec.decode(encoded))
        assertTrue(session.verifier().verify(decoded.stock.receipt.canonicalBytes(), decoded.stock.signature))
        assertTrue(session.verifier().verify(checkNotNull(decoded.exited).receipt.canonicalBytes(), decoded.exited.signature))
        val tamperedStockOffset = Int.SIZE_BYTES * 3 + stockReceipt.canonicalBytes().lastIndex
        assertNull(
            GuardianTerminalWireCodec.decode(
                encoded.copyOf().also { bytes ->
                    bytes[tamperedStockOffset] = (bytes[tamperedStockOffset].toInt() xor 0x01).toByte()
                },
            ),
        )

        val store = GuardianTerminalReceiptStore(temporaryFolder.newFolder("terminal").absoluteFile)
        assertTrue(store.persist(stock, exited))
        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Received)
        assertTrue(store.clear())
        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Unavailable)
    }

    @Test
    fun `cross-process fence terminalizes before restore and blocks every later apply`() {
        val fence = GuardianProcessMutationFence(temporaryFolder.newFolder("fence").absoluteFile)
        val identity = GenerationFenceIdentity(
            bootIdentity = "boot-1",
            generation = 7,
            transactionId = "transaction-7",
            sessionId = "c".repeat(32),
        )

        assertEquals(GuardianFenceDisposition.CONFIRMED, fence.stage(identity))
        assertEquals(GuardianFenceDisposition.CONFIRMED, fence.stage(identity))
        assertEquals("apply", (fence.withApply(identity) { "apply" } as GuardianFencedResult.Executed).value)
        assertEquals(
            "restore",
            (fence.terminalizeAndRestore(identity) { "restore" } as GuardianFencedResult.Executed).value,
        )
        assertTrue(fence.withApply(identity) { error("must not execute") } is GuardianFencedResult.Terminal)
        val state = fence.state(identity)
        assertTrue(state.directoryPresent)
        assertTrue(state.lockAcquired)
        assertEquals(identity, state.sessionMarkerIdentity)
        assertEquals(identity, state.terminalMarkerIdentity)
        assertTrue(fence.clearTerminal(identity))
        assertEquals(GuardianFenceDisposition.CONFIRMED, fence.stage(identity))
    }

    @Test
    fun `terminal fence permits readback retry while keeping every later apply blocked`() {
        val fence = GuardianProcessMutationFence(temporaryFolder.newFolder("retry-fence").absoluteFile)
        val identity = GenerationFenceIdentity(
            bootIdentity = "boot-1",
            generation = 8,
            transactionId = "transaction-8",
            sessionId = "d".repeat(32),
        )
        val terminalWasAbsent = mutableListOf<Boolean>()

        assertEquals(GuardianFenceDisposition.CONFIRMED, fence.stage(identity))
        assertEquals(
            "first-readback",
            (
                fence.terminalizeAndRestoreWithPrior(identity) { first ->
                    terminalWasAbsent += first
                    "first-readback"
                } as GuardianFencedResult.Executed
            ).value,
        )
        assertTrue(
            fence.withApply(identity) { error("terminal fence must block mutation") } is
                GuardianFencedResult.Terminal,
        )
        assertEquals(
            "retry-readback",
            (
                fence.terminalizeAndRestoreWithPrior(identity) { first ->
                    terminalWasAbsent += first
                    "retry-readback"
                } as GuardianFencedResult.Executed
            ).value,
        )
        assertEquals(listOf(true, false), terminalWasAbsent)
        assertTrue(
            fence.withApply(identity) { error("retry must not reopen mutation") } is
                GuardianFencedResult.Terminal,
        )
    }

    @Test
    fun `guardian retries only an ambiguous terminal fence and remains bounded`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val recovered = GuardianFenceAmbiguityRetry.execute<String>(sleeps::add) {
            calls += 1
            if (calls == 1) GuardianFencedResult.Ambiguous else GuardianFencedResult.Executed("stock")
        }
        assertEquals("stock", (recovered as GuardianFencedResult.Executed).value)
        assertEquals(2, calls)
        assertEquals(listOf(GuardianFenceAmbiguityRetry.RETRY_INTERVAL_MS), sleeps)

        calls = 0
        val rejected = GuardianFenceAmbiguityRetry.execute<String>({ error("must not sleep") }) {
            calls += 1
            GuardianFencedResult.Rejected
        }
        assertTrue(rejected is GuardianFencedResult.Rejected)
        assertEquals(1, calls)

        calls = 0
        val exhausted = GuardianFenceAmbiguityRetry.execute<String>({}) {
            calls += 1
            GuardianFencedResult.Ambiguous
        }
        assertTrue(exhausted is GuardianFencedResult.Ambiguous)
        assertEquals(GuardianFenceAmbiguityRetry.MAX_RETRIES + 1, calls)
    }

    @Test
    fun `Guardian retry queue requeues one exact token and settles within its budget`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "guardian-test-worker")
        }
        try {
            val retry = GuardianSameGenerationRestoreRetry(
                scheduler = scheduler,
                worker = worker,
                maxAttempts = 3,
                initialDelayMs = 0,
                maxDelayMs = 0,
            )
            val attempts = AtomicInteger()
            val attemptStarted = CountDownLatch(2)
            val workerBlocked = CountDownLatch(1)
            val workerStarted = CountDownLatch(1)
            worker.execute {
                workerStarted.countDown()
                workerBlocked.await(2, TimeUnit.SECONDS)
            }
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS))
            val token = Any()
            retry.schedule(
                token = token,
                guard = { true },
                attempt = {
                    if (attempts.incrementAndGet() == 1) {
                        attemptStarted.countDown()
                        GuardianSameGenerationRetryResult.RETRY
                    } else {
                        attemptStarted.countDown()
                        GuardianSameGenerationRetryResult.SETTLED
                    }
                },
            )
            assertFalse("scheduler must not run Guardian work inline", attemptStarted.await(100, TimeUnit.MILLISECONDS))
            workerBlocked.countDown()
            assertTrue(attemptStarted.await(2, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())

            val cancelled = GuardianSameGenerationRestoreRetry(
                scheduler = scheduler,
                worker = worker,
                maxAttempts = 2,
                initialDelayMs = 0,
                maxDelayMs = 0,
            )
            val cancelBlocker = CountDownLatch(1)
            val cancelStarted = CountDownLatch(1)
            worker.execute {
                cancelStarted.countDown()
                cancelBlocker.await(2, TimeUnit.SECONDS)
            }
            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS))
            val cancelledAttempts = AtomicInteger()
            cancelled.schedule(
                token = Any(),
                guard = { true },
                attempt = {
                    cancelledAttempts.incrementAndGet()
                    GuardianSameGenerationRetryResult.SETTLED
                },
            )
            Thread.sleep(100)
            cancelled.cancel()
            cancelBlocker.countDown()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals("cancelled queued retry must not enter Guardian work", 0, cancelledAttempts.get())

            val exhausted = GuardianSameGenerationRestoreRetry(
                scheduler = scheduler,
                worker = worker,
                maxAttempts = 2,
                initialDelayMs = 0,
                maxDelayMs = 0,
            )
            val exhaustedAttempts = AtomicInteger()
            val exhaustedDone = CountDownLatch(2)
            exhausted.schedule(
                token = Any(),
                guard = { true },
                attempt = {
                    exhaustedAttempts.incrementAndGet()
                    exhaustedDone.countDown()
                    GuardianSameGenerationRetryResult.RETRY
                },
            )
            assertTrue(exhaustedDone.await(2, TimeUnit.SECONDS))
            assertEquals(2, exhaustedAttempts.get())
            exhausted.cancel()
            retry.schedule(token, guard = { true }) {
                attempts.incrementAndGet()
                GuardianSameGenerationRetryResult.RETRY
            }
            assertEquals("settled retry must not re-arm the queue", 2, attempts.get())
            retry.cancel()
            cancelled.cancel()
        } finally {
            scheduler.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun `owned terminal persistence retains an unrelated frame and retries an own failed write`() {
        val authority = checkNotNull(GuardianProcessSessionAuthority.generate())
        val session = authority.sessionPublicKey
        val stockReceipt = RestoreGuardianStockConfirmedReceipt(
            helperBuildIdentity = "helper-1",
            bootIdentity = "boot-1",
            generation = 2,
            requestId = "request-2",
            transactionId = "transaction-2",
            taskNonce = "a".repeat(32),
            envelopeSha256 = "b".repeat(64),
            sessionKeySha256 = session.sha256(),
            firstReadbackElapsedRealtimeMs = 2_000,
            secondReadbackElapsedRealtimeMs = 2_100,
            issuedElapsedRealtimeMs = 2_100,
        )
        val stock = SignedRestoreGuardianStockConfirmedReceipt(
            stockReceipt,
            checkNotNull(authority.authenticate(GuardianReceiptType.STOCK_CONFIRMED, stockReceipt.canonicalBytes())),
        )
        val exitedReceipt = RestoreGuardianExitedReceipt(
            helperBuildIdentity = "helper-1",
            bootIdentity = "boot-1",
            generation = 2,
            requestId = "request-2",
            transactionId = "transaction-2",
            taskNonce = "a".repeat(32),
            envelopeSha256 = "b".repeat(64),
            sessionKeySha256 = session.sha256(),
            stockConfirmedReceiptSha256 = stockReceipt.digestSha256(),
            issuedElapsedRealtimeMs = 2_101,
        )
        val exited = SignedRestoreGuardianExitedReceipt(
            exitedReceipt,
            checkNotNull(authority.authenticate(GuardianReceiptType.EXITED, exitedReceipt.canonicalBytes())),
        )
        val root = temporaryFolder.newFolder("owned-terminal")
        val staging = java.io.File(root, ".mimir_guardian_terminal_receipts.v1.tmp")
        staging.writeBytes(byteArrayOf(9, 8, 7))
        val store = GuardianTerminalReceiptStore(root)
        assertEquals(
            GuardianTerminalPersistDisposition.RETAINED,
            store.persistOwnedPending(stock, exited),
        )
        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Ambiguous)
        assertTrue(staging.exists())

        staging.delete()
        val brokenRoot = temporaryFolder.newFile("broken-terminal")
        val brokenStore = GuardianTerminalReceiptStore(brokenRoot)
        assertEquals(
            GuardianTerminalPersistDisposition.RETRYABLE,
            brokenStore.persistOwnedPending(stock, exited),
        )
        assertFalse(brokenRoot.isDirectory)
        assertTrue(brokenRoot.delete())
        assertTrue(brokenRoot.mkdirs())
        assertEquals(
            GuardianTerminalPersistDisposition.CONFIRMED,
            brokenStore.persistOwnedPending(stock, exited),
        )
        assertTrue(brokenStore.loadUntil(0) is GuardianTerminalLoad.Received)
    }
}
