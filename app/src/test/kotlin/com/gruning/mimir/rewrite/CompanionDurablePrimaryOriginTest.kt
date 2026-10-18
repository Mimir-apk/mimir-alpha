package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDurablePrimaryOriginTest {
    @Test
    fun `newer current origin survives process recovery and state version restart`() {
        val backend = InMemoryBackend()
        val firstProcess = CompanionDurablePersistence(backend, processEpoch = "process-a")
        val launchOrigin = origin("LIBRARY", revision = 1L, "focusKey" to "game:a")
        val currentOrigin = origin("SETTINGS", revision = 2L, "section" to "input")
        val pending = pending(operation(1), launchOrigin)

        assertTrue(firstProcess.persistCurrentOrigin(launchOrigin))
        assertEquals(
            CompanionPersistenceWriteResult.WRITTEN,
            firstProcess.writePending(pending, stateVersion = 41L),
        )
        assertTrue(firstProcess.persistCurrentOrigin(currentOrigin))

        val restarted = CompanionDurablePersistence(backend, processEpoch = "process-b")
        val recovered = requireNotNull(restarted.recoverPending())
        assertEquals(currentOrigin, recovered.primaryOrigin)

        val runtime = CompanionRuntimeCoordinator(NoOpScheduler)
        assertTrue(runtime.recoverPersistedNonMirror(recovered))
        val recovery = runtime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(currentOrigin, recovery.fallback)

        assertEquals(
            CompanionPersistenceWriteResult.WRITTEN,
            restarted.writePending(recovered, stateVersion = 1L),
        )
        assertEquals(currentOrigin, restarted.currentOrigin())
    }

    @Test
    fun `live host return selects only a strictly newer current origin`() {
        val runtime = CompanionRuntimeCoordinator(NoOpScheduler)
        val launchOrigin = origin("LIBRARY", revision = 7L, "focusKey" to "game:a")
        val newerOrigin = origin("SETTINGS", revision = 8L, "section" to "display")
        assertTrue(runtime.displayAvailable(4))
        val host = runtime.launchRequested("example.game", 4, "secondary_app", launchOrigin)!!
        assertTrue(runtime.hostStarted(host.operationId))
        assertTrue(runtime.targetDispatched(host.operationId))
        assertTrue(runtime.hostCovered(host.operationId))

        val reclaim = runtime.hostReturned(host.operationId, newerOrigin)!!
        assertEquals(newerOrigin, reclaim.primaryOrigin)

        val recoveryRuntime = CompanionRuntimeCoordinator(NoOpScheduler)
        assertTrue(
            recoveryRuntime.recoverPersistedNonMirror(
                pending(operation(2), launchOrigin),
                currentPrimaryOrigin = newerOrigin,
            ),
        )
        val recovery = recoveryRuntime.snapshot.value.disposition as SecondaryDisplayDisposition.RecoveryNeeded
        assertEquals(newerOrigin, recovery.fallback)
    }

    @Test
    fun `stale and equal-version writes cannot overwrite newer origins or pending state`() {
        val backend = InMemoryBackend()
        val persistence = CompanionDurablePersistence(backend, processEpoch = "process-a")
        val oldOrigin = origin("LIBRARY", revision = 3L, "focusKey" to "game:a")
        val equalVersionNewerOrigin = origin("SETTINGS", revision = 4L, "section" to "library")
        val durableCurrent = origin("BROWSE", revision = 5L, "filter" to "hidden")
        val first = pending(operation(3), oldOrigin)

        assertEquals(
            CompanionPersistenceWriteResult.WRITTEN,
            persistence.writePending(first, stateVersion = 10L),
        )
        assertEquals(
            CompanionPersistenceWriteResult.WRITTEN,
            persistence.writePending(first.copy(primaryOrigin = equalVersionNewerOrigin), stateVersion = 10L),
        )
        assertEquals(equalVersionNewerOrigin, persistence.recoverPending()!!.primaryOrigin)
        assertTrue(persistence.persistCurrentOrigin(durableCurrent))

        val latest = pending(operation(4), equalVersionNewerOrigin)
        assertEquals(
            CompanionPersistenceWriteResult.WRITTEN,
            persistence.writePending(latest, stateVersion = 12L),
        )
        val beforeStaleCollector = backend.read()
        assertEquals(
            CompanionPersistenceWriteResult.IGNORED_STALE,
            persistence.writePending(first, stateVersion = 11L),
        )

        assertEquals(beforeStaleCollector, backend.read())
        assertEquals(operation(4), CompanionDurableRecordCodec.decode(backend.read()).pendingLease!!.operationId)
        assertEquals(durableCurrent, persistence.currentOrigin())
        assertEquals(durableCurrent, persistence.recoverPending()!!.primaryOrigin)
    }

    @Test
    fun `out of order queued origin writes coalesce and preserve pending state`() {
        val backend = InMemoryBackend()
        val persistence = CompanionDurablePersistence(backend, processEpoch = "process-a")
        val launchOrigin = origin("LIBRARY", revision = 1L, "focusKey" to "game:a")
        val pending = pending(operation(7), launchOrigin)
        assertEquals(
            CompanionPersistenceWriteResult.WRITTEN,
            persistence.writePending(pending, stateVersion = 14L),
        )

        val dispatched = mutableListOf<() -> Unit>()
        val persistedRevisions = mutableListOf<Long>()
        val queue = CompanionOriginWriteQueue(
            dispatcher = CompanionOriginWriteDispatcher { action -> dispatched += action },
            initialRevision = 0L,
            persist = { origin ->
                persistedRevisions += origin.revision
                persistence.persistCurrentOrigin(origin)
            },
        )
        val middle = origin("SETTINGS", revision = 2L, "section" to "input")
        val stale = origin("BROWSE", revision = 1L, "filter" to "all")
        val newest = origin("BROWSE", revision = 4L, "filter" to "hidden")

        queue.enqueue(middle)
        queue.enqueue(stale)
        queue.enqueue(newest)

        assertEquals(1, dispatched.size)
        assertNull(persistence.currentOrigin())
        dispatched.removeAt(0).invoke()

        assertEquals(listOf(4L), persistedRevisions)
        assertEquals(newest, persistence.currentOrigin())
        val durable = CompanionDurableRecordCodec.decode(backend.read())
        assertEquals(14L, durable.stateVersion)
        assertEquals(pending, durable.pendingLease)

        queue.enqueue(middle)
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `origin queue retries transient persistence failure without losing revision`() {
        val dispatched = mutableListOf<() -> Unit>()
        var attempts = 0
        val persisted = mutableListOf<PrimaryReturnOrigin>()
        val queue = CompanionOriginWriteQueue(
            dispatcher = CompanionOriginWriteDispatcher { action -> dispatched += action },
            initialRevision = 0L,
            persist = { origin ->
                attempts += 1
                if (attempts < 3) false else true.also { persisted += origin }
            },
        )
        val origin = origin("LIBRARY", revision = 4L, "focusKey" to "library/shelf/ps1")

        queue.enqueue(origin)
        dispatched.single().invoke()

        assertEquals(3, attempts)
        assertEquals(listOf(origin), persisted)
    }

    @Test
    fun `failed revision can retry after the backend becomes writable`() {
        val dispatched = mutableListOf<() -> Unit>()
        val failures = mutableListOf<PrimaryReturnOrigin>()
        var writable = false
        val persisted = mutableListOf<PrimaryReturnOrigin>()
        val queue = CompanionOriginWriteQueue(
            dispatcher = CompanionOriginWriteDispatcher { action -> dispatched += action },
            initialRevision = 0L,
            persist = { origin -> writable.also { if (it) persisted += origin } },
            onFailure = failures::add,
        )
        val origin = origin("SETTINGS", revision = 8L, "section" to "library")

        queue.enqueue(origin)
        dispatched.removeAt(0).invoke()
        assertEquals(listOf(origin), failures)
        assertTrue(dispatched.isEmpty())

        writable = true
        queue.retry(failures.single())
        dispatched.removeAt(0).invoke()

        assertEquals(listOf(origin), persisted)
    }

    @Test
    fun `legacy records recover unversioned while malformed schemas discard unsafe origins`() {
        val legacyOrigin = origin("SETTINGS", revision = 0L, "section" to "about")
        val legacyBackend = InMemoryBackend(
            mapOf(
                CompanionDurableRecordCodec.Operation to operation(5).toString(),
                CompanionDurableRecordCodec.Display to 4,
                CompanionDurableRecordCodec.Destination to legacyOrigin.destination,
                CompanionDurableRecordCodec.Description to "legacy pending lease",
                CompanionDurableRecordCodec.ArgPrefix + "section" to "about",
            ),
        )
        val legacy = CompanionDurablePersistence(legacyBackend, "process-a").recoverPending()
        assertNotNull(legacy)
        assertEquals(legacyOrigin, legacy!!.primaryOrigin)

        val malformedBackend = InMemoryBackend(
            mapOf(
                CompanionDurableRecordCodec.Schema to 99,
                CompanionDurableRecordCodec.Operation to operation(6).toString(),
                CompanionDurableRecordCodec.Display to 4,
                CompanionDurableRecordCodec.Destination to "SETTINGS",
                CompanionDurableRecordCodec.OriginRevision to Long.MAX_VALUE,
                CompanionDurableRecordCodec.CurrentDestination to "BROWSE",
                CompanionDurableRecordCodec.CurrentRevision to Long.MAX_VALUE,
            ),
        )
        val malformed = CompanionDurablePersistence(malformedBackend, "process-b").recoverPending()
        assertNotNull(malformed)
        assertNull(malformed!!.primaryOrigin)
        assertNull(CompanionDurablePersistence(malformedBackend, "process-b").currentOrigin())
    }

    private fun origin(
        destination: String,
        revision: Long,
        vararg arguments: Pair<String, String>,
    ) = PrimaryReturnOrigin(destination, mapOf(*arguments), revision)

    private fun pending(
        operationId: OperationId,
        origin: PrimaryReturnOrigin,
    ) = PersistedNonMirrorLease(
        operationId = operationId,
        displayId = 4,
        primaryOrigin = origin,
        description = "test pending lease",
    )

    private fun operation(value: Int): OperationId = requireNotNull(
        OperationId.parseOrNull("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"),
    )

    private class InMemoryBackend(
        initial: Map<String, Any> = emptyMap(),
    ) : CompanionPersistenceBackend {
        private var values = initial.toMap()

        override fun read(): Map<String, *> = values.toMap()

        override fun replace(values: Map<String, Any>): Boolean {
            this.values = values.toMap()
            return true
        }
    }

    private object NoOpScheduler : CompanionRuntimeScheduler {
        override fun schedule(
            delayMillis: Long,
            operationId: OperationId,
            action: () -> Unit,
        ): RuntimeCancellation = RuntimeCancellation {}
    }
}
