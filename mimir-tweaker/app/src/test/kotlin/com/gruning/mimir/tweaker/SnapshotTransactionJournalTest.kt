package com.gruning.mimir.tweaker

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S9 "Status disk access": public truth and status called `journal.load()`, which takes the journal's monitor and the
 * process-wide journal lock and then reads a file and verifies its authentication. A journal write that was slow or
 * stuck therefore blocked the status of the very operation that was stuck, on a Binder thread. Public truth now reads
 * [SnapshotTransactionJournal.snapshot], which never touches the delegate once anything has been read.
 */
class SnapshotTransactionJournalTest {
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val latches = mutableListOf<CountDownLatch>()

    @After
    fun tearDown() {
        latches.forEach(CountDownLatch::countDown)
        executor.shutdownNow()
    }

    private fun latch() = CountDownLatch(1).also(latches::add)

    /** A journal whose `load()` can be wedged, and whose state is observable without a real record. */
    private class FakeJournal : TransactionJournal {
        @Volatile var state: JournalLoadResult = JournalLoadResult.Empty
        @Volatile var gate: CountDownLatch? = null
        @Volatile var entered: CountDownLatch? = null
        @Volatile var throwOnLoad = false
        val loads = AtomicInteger(0)
        val persists = AtomicInteger(0)

        override fun load(): JournalLoadResult {
            loads.incrementAndGet()
            entered?.countDown()
            gate?.await()
            if (throwOnLoad) throw java.io.IOException("journal gone")
            return state
        }

        override fun nextGeneration(): GenerationResult = GenerationResult.Ok(1L)

        override fun persist(record: TransactionJournalRecord): Boolean {
            persists.incrementAndGet()
            state = JournalLoadResult.Corrupt("after-persist")
            return true
        }

        override fun clear(expectedTransactionId: String): Boolean {
            state = JournalLoadResult.Empty
            return true
        }
    }

    @Test
    fun `a snapshot read does not wait on a journal load that is wedged`() {
        val delegate = FakeJournal()
        val subject = SnapshotTransactionJournal(delegate)
        assertEquals("primed by the first load", JournalLoadResult.Empty, subject.snapshot())

        // A recovery or settlement load is now stuck inside the delegate, holding the journal lock.
        val wedge = latch()
        val entered = latch()
        delegate.gate = wedge
        delegate.entered = entered
        executor.execute { subject.load() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))

        val status = executor.submit<JournalLoadResult> { subject.snapshot() }
        val answered = runCatching { status.get(2, TimeUnit.SECONDS) }
        assertTrue("public truth must not wait on the journal: ${answered.exceptionOrNull()}", answered.isSuccess)
        assertEquals(JournalLoadResult.Empty, answered.getOrNull())
        assertEquals("and it read nothing", 2, delegate.loads.get())
    }

    @Test
    fun `the first snapshot of a process loads once, and later ones never do`() {
        val delegate = FakeJournal()
        val subject = SnapshotTransactionJournal(delegate)

        subject.snapshot()
        subject.snapshot()
        subject.snapshot()

        assertEquals(1, delegate.loads.get())
    }

    @Test
    fun `a write is visible in the snapshot at once, so it is never older than the last write`() {
        val delegate = FakeJournal()
        val subject = SnapshotTransactionJournal(delegate)
        assertEquals(JournalLoadResult.Empty, subject.snapshot())

        assertTrue(subject.persist(sampleRecord()))
        assertEquals(JournalLoadResult.Corrupt("after-persist"), subject.snapshot())

        assertTrue(subject.clear("tx"))
        assertEquals(JournalLoadResult.Empty, subject.snapshot())
    }

    @Test
    fun `a load refreshes the snapshot`() {
        val delegate = FakeJournal()
        val subject = SnapshotTransactionJournal(delegate)
        subject.snapshot()
        delegate.state = JournalLoadResult.Corrupt("changed underneath")

        assertEquals(JournalLoadResult.Corrupt("changed underneath"), subject.load())
        assertEquals(JournalLoadResult.Corrupt("changed underneath"), subject.snapshot())
    }

    @Test
    fun `a re-read that fails after a write is recorded as corrupt, never as empty`() {
        val delegate = FakeJournal()
        val subject = SnapshotTransactionJournal(delegate)
        subject.snapshot()
        delegate.throwOnLoad = true

        subject.persist(sampleRecord())

        val snapshot = subject.snapshot()
        assertTrue("an unreadable journal must never read as stock: $snapshot", snapshot is JournalLoadResult.Corrupt)
    }

    @Test
    fun `a generation number does not move the snapshot or read the journal`() {
        val delegate = FakeJournal()
        val subject = SnapshotTransactionJournal(delegate)
        val before = subject.snapshot()
        val loads = delegate.loads.get()

        assertEquals(GenerationResult.Ok(1L), subject.nextGeneration())

        assertSame(before, subject.snapshot())
        assertEquals(loads, delegate.loads.get())
    }

    /** The service must build public truth from the snapshot, and keep `journal.load()` out of it. */
    @Test
    fun `public truth reads the snapshot and never the journal`() {
        val service = source("MimirTweakerService.kt")
        val truth = service.substringAfter("private fun publicSessionTruth(): PublicSessionTruth {").substringBefore("\n    }\n")
        assertTrue(truth.contains("journalSnapshots.snapshot()"))
        assertFalse("public truth must not load the journal", truth.contains("journal.load()"))
        assertTrue(
            "the snapshot journal must be the one the service mutates through",
            service.contains("journal = journalSnapshots"),
        )
    }

    private fun sampleRecord(): TransactionJournalRecord = TransactionJournalRecord(
        schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
        generation = 1,
        requestId = "req",
        transactionId = "tx",
        hardwareFingerprint = "fp",
        deviceProfile = "retroid-qcs8550-pserver",
        planHash = "ph",
        profileId = "quiet",
        targetPackage = "",
        phase = TransactionPhase.APPLYING,
        creationTime = 1,
        requestDeadline = 2,
        leaseDeadline = 3,
        bootIdentity = "boot",
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fp",
            bootIdentity = "boot",
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "e",
        snapshotSha256 = "s",
        lastCompletedMutationBoundary = 0,
    )

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
