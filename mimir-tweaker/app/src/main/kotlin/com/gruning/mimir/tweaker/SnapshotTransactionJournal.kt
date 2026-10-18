package com.gruning.mimir.tweaker

/**
 * The transaction journal with a lock-free in-memory view of what it last said, for the one reader that must never wait:
 * public truth and status.
 *
 * [AtomicFileTransactionJournal.load] takes the journal's monitor and the process-wide journal lock, then reads a file
 * and verifies its authentication (a key-store call). Every public answer, including the SERVICE_BUSY answer for a
 * caller the worker just timed out, used to do that on the Binder thread, so a journal write that was slow (or stuck)
 * blocked the status of the very operation that was stuck. [snapshot] returns the last observed value without touching
 * the delegate.
 *
 * **The snapshot is never older than the last journal write this process made.** Every mutating call, successful or
 * not, re-reads the journal before it returns, under the same lock that serialises the mutation and every [load], so
 * two writers cannot publish out of order. A re-read that fails is recorded as [JournalLoadResult.Corrupt]: an
 * unreadable journal is never reported as an empty one. Callers that must act on the durable record (recovery,
 * settlement, restore) keep calling [load], which also refreshes the snapshot.
 *
 * The only blocking [snapshot] is the very first one in a process that has not loaded yet; after that it is a volatile
 * read. (A write made by another process would not show until the next [load]; this journal has no other writer.)
 */
internal class SnapshotTransactionJournal(
    private val delegate: TransactionJournal,
) : TransactionJournal {
    private val lock = Any()

    @Volatile
    private var latest: JournalLoadResult? = null

    /** What the journal last said, without reading it. Loads once if nothing has been read yet. */
    fun snapshot(): JournalLoadResult = latest ?: load()

    override fun load(): JournalLoadResult = synchronized(lock) {
        delegate.load().also { latest = it }
    }

    // A generation number advances without changing the journal record, so it never moves the snapshot.
    override fun nextGeneration(): GenerationResult = delegate.nextGeneration()

    override fun persist(record: TransactionJournalRecord): Boolean = mutating { delegate.persist(record) }

    override fun amendApplied(record: TransactionJournalRecord): Boolean = mutating { delegate.amendApplied(record) }

    override fun amendFanOwnership(record: TransactionJournalRecord, envelope: RestoreGuardianEnvelope): Boolean =
        mutating { delegate.amendFanOwnership(record, envelope) }

    override fun clear(expectedTransactionId: String): Boolean = mutating { delegate.clear(expectedTransactionId) }

    private inline fun <T> mutating(operation: () -> T): T = synchronized(lock) {
        try {
            operation()
        } finally {
            // Whether the write succeeded, failed or threw, the durable record is whatever it now is: say that.
            latest = try {
                delegate.load()
            } catch (error: Throwable) {
                JournalLoadResult.Corrupt("Journal re-read after a write failed: ${error.javaClass.simpleName}")
            }
        }
    }
}
