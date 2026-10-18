package com.gruning.mimir.rewrite.core.storage

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Opaque witness for one process-local source/index state. */
class SourceIndexGeneration internal constructor(
    /**
     * Persistence-safe only as an equality witness. It embeds a random process incarnation, so a cursor
     * from a dead process fails closed and restarts instead of trusting a reset in-memory counter.
     */
    val persistenceKey: String,
    internal val value: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SourceIndexGeneration &&
            persistenceKey == other.persistenceKey &&
            value == other.value

    override fun hashCode(): Int = 31 * persistenceKey.hashCode() + value.hashCode()
}

/** A read result bound to the exact source/index generation under which it was selected. */
data class SourceIndexSnapshot<T>(
    val generation: SourceIndexGeneration,
    val value: T,
)

/** Process-local witness for source-registry/presence truth, excluding unrelated identity-index writes. */
class SourceMutationGeneration internal constructor(
    private val processIncarnation: String,
    internal val value: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SourceMutationGeneration &&
            processIncarnation == other.processIncarnation &&
            value == other.value

    override fun hashCode(): Int = 31 * processIncarnation.hashCode() + value.hashCode()
}

/** A source-registry read bound to the source-mutation generation under which it was selected. */
data class SourceMutationSnapshot<T>(
    val generation: SourceMutationGeneration,
    val value: T,
)

/** Why a guarded healthy scan did or did not publish. */
enum class SourceScanCommitResult {
    COMMITTED,
    /** Source truth changed after the walk began; retry without recording a source outcome. */
    STALE,
    /** The latest mounted-volume truth says this exact root is absent. */
    ABSENT,
}

/**
 * Process-local ordering authority for source scans and live storage-presence snapshots.
 *
 * Walking a large source deliberately happens outside this gate. Only the final Room mutation is
 * serialized with volume reconciliation, and it is rejected when a newer mounted-volume snapshot says
 * the exact source is absent. Therefore either:
 * - scan commits first and the detach mutation commits after it, or
 * - detach commits first and the stale scan cannot write AVAILABLE rows over that truth.
 *
 * Room remains the durable transaction authority. This coordinator only closes the cross-component
 * interleaving window inside one app process; startup volume reconciliation supplies truth after process
 * death.
 */
class SourceRootMutationCoordinator(
    private val processIncarnation: String = UUID.randomUUID().toString(),
) {
    private enum class Presence { PRESENT, ABSENT }

    private val mutex = Mutex()
    private val presenceByRoot = mutableMapOf<String, Presence>()
    private var latestRootPresence: ((String) -> Boolean)? = null
    private var indexGeneration: Long = 0L
    private var sourceMutationRevision: Long = 0L
    private val mutableSourceMutations = MutableStateFlow(0L)

    /**
     * Successful source/availability mutations. App orchestration uses this to append one trailing
     * identity pass; a same-count same-key replacement must not depend on a distinct Room count emission.
     */
    val sourceMutations: StateFlow<Long> = mutableSourceMutations.asStateFlow()

    /**
     * Selects background index work and captures its generation atomically with respect to every source,
     * availability, and guarded identity mutation. Expensive file I/O must happen after this returns.
     */
    suspend fun <T> snapshotIndex(read: suspend () -> T): SourceIndexSnapshot<T> {
        mutex.lock()
        return try {
            SourceIndexSnapshot(currentGenerationLocked(), read())
        } finally {
            mutex.unlock()
        }
    }

    /** Captures the current process-local witness for a durable cursor continuity check. */
    suspend fun currentIndexGeneration(): SourceIndexGeneration {
        mutex.lock()
        return try {
            currentGenerationLocked()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Selects source-registry work under the same authority as scan publication and volume callbacks.
     * Identity-only index writes deliberately do not invalidate this narrower witness.
     */
    suspend fun <T> snapshotSourceMutation(read: suspend () -> T): SourceMutationSnapshot<T> {
        mutex.lock()
        return try {
            SourceMutationSnapshot(currentSourceMutationGenerationLocked(), read())
        } finally {
            mutex.unlock()
        }
    }

    /** Captures the current process-local source-registry witness before an expensive walk. */
    suspend fun currentSourceMutationGeneration(): SourceMutationGeneration {
        mutex.lock()
        return try {
            currentSourceMutationGenerationLocked()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs bookkeeping only when source truth has not changed since [expectedGeneration].
     *
     * The mutation itself does not change source truth, so it does not advance the source revision.
     */
    suspend fun <T> commitIfSourceUnchanged(
        expectedGeneration: SourceMutationGeneration,
        mutation: suspend () -> T,
    ): Boolean {
        mutex.lock()
        return try {
            if (expectedGeneration != currentSourceMutationGenerationLocked()) {
                false
            } else {
                mutation()
                true
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Publishes background index work only if no ordered mutation occurred since [snapshotIndex].
     *
     * The mutation runs while holding the same gate as scan publication. A successful publication advances
     * the generation so another worker holding the same old witness cannot publish a conflicting snapshot.
     */
    suspend fun commitIndexIfUnchanged(
        generation: SourceIndexGeneration,
        mutation: suspend () -> Unit,
    ): Boolean = commitIndexIfUnchangedAndGet(generation, mutation) != null

    /**
     * [commitIndexIfUnchanged] plus the post-commit witness. The returned generation lets a paged reader
     * prove that no source mutation landed between two keyset pages.
     */
    suspend fun commitIndexIfUnchangedAndGet(
        generation: SourceIndexGeneration,
        mutation: suspend () -> Unit,
    ): SourceIndexGeneration? {
        mutex.lock()
        return try {
            if (generation != currentGenerationLocked()) {
                null
            } else {
                // Invalidate peer witnesses before the first write. If the mutation throws after a
                // lower-level partial side effect, no holder may reuse the pre-mutation witness.
                advanceIndexGeneration()
                mutation()
                currentGenerationLocked()
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Runs a short index mutation exclusively and invalidates every outstanding snapshot witness. */
    suspend fun <T> commitIndexMutation(mutation: suspend () -> T): T {
        mutex.lock()
        return try {
            advanceIndexGeneration()
            mutation()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs a short source mutation and publishes a fresh trailing-identity revision.
     *
     * A resumed durable reconciliation calls this once because a source commit made by the process that
     * died may have advanced only that process's in-memory revision. This is at-least-once notification,
     * not a cross-process lease or exactly-once outbox.
     */
    suspend fun <T> commitSourceMutation(mutation: suspend () -> T): T {
        var sourceRevisionToPublish: Long? = null
        mutex.lock()
        return try {
            sourceRevisionToPublish = advanceSourceIndexGeneration()
            mutation()
        } finally {
            mutex.unlock()
            sourceRevisionToPublish?.let { mutableSourceMutations.value = it }
        }
    }

    /**
     * Records one mounted-volume snapshot and applies its Room mutation under the same gate. Mounted
     * truth wins if a malformed caller lists a root as both present and absent.
     */
    suspend fun <T> applyPresenceSnapshot(
        absentRootUris: List<String>,
        presentRootUris: List<String>,
        /**
         * Classifies any root against the same immutable volume snapshot, including a first-add root
         * that was not present in source_roots when the callback queried Room.
         */
        rootPresentAtSnapshot: ((String) -> Boolean)? = null,
        mutation: suspend () -> T,
    ): T {
        val present = presentRootUris.filter(String::isNotBlank).distinct()
        val absent = absentRootUris.filter(String::isNotBlank).distinct().filterNot(present::contains)
        var sourceRevisionToPublish: Long? = null
        mutex.lock()
        return try {
            if (rootPresentAtSnapshot != null) latestRootPresence = rootPresentAtSnapshot
            absent.forEach { presenceByRoot[it] = Presence.ABSENT }
            present.forEach { presenceByRoot[it] = Presence.PRESENT }
            // The immutable volume observation itself invalidates outstanding file-incarnation work,
            // even if the caller's subsequent Room mutation fails.
            sourceRevisionToPublish = advanceSourceIndexGeneration()
            mutation()
        } finally {
            mutex.unlock()
            sourceRevisionToPublish?.let { mutableSourceMutations.value = it }
        }
    }

    /**
     * Commits a completed walk only when no newer volume snapshot marks that exact root absent.
     * Unknown roots are allowed: the startup volume pass may not have run yet, and if it races after
     * this commit it owns the later write.
     */
    suspend fun commitScanIfPresent(
        rootUri: String,
        mutation: suspend () -> Unit,
    ): Boolean = commitScanIfCurrent(
        rootUri = rootUri,
        expectedGeneration = null,
        mutation = mutation,
    ) == SourceScanCommitResult.COMMITTED

    /**
     * Guarded scan publication that distinguishes stale work from authoritative absence.
     *
     * This distinction prevents an unrelated source mutation from being misreported as the scanned root
     * becoming unavailable.
     */
    suspend fun commitScanIfCurrent(
        rootUri: String,
        expectedGeneration: SourceMutationGeneration?,
        mutation: suspend () -> Unit,
    ): SourceScanCommitResult {
        var sourceRevisionToPublish: Long? = null
        mutex.lock()
        return try {
            val presentInLatestVolumeSnapshot = latestRootPresence?.let { classifier ->
                runCatching { classifier(rootUri) }.getOrNull()
            }
            if (expectedGeneration != null &&
                expectedGeneration != currentSourceMutationGenerationLocked()
            ) {
                SourceScanCommitResult.STALE
            } else if (presentInLatestVolumeSnapshot == false ||
                (presentInLatestVolumeSnapshot == null && presenceByRoot[rootUri] == Presence.ABSENT)
            ) {
                SourceScanCommitResult.ABSENT
            } else {
                sourceRevisionToPublish = advanceSourceIndexGeneration()
                mutation()
                SourceScanCommitResult.COMMITTED
            }
        } finally {
            mutex.unlock()
            sourceRevisionToPublish?.let { mutableSourceMutations.value = it }
        }
    }

    /** Confirmed source removal is serialized too, then forgets stale presence for a future re-add. */
    suspend fun commitRootRemoval(
        rootUri: String,
        expectedGeneration: SourceMutationGeneration? = null,
        mutation: suspend () -> Unit,
    ): Boolean {
        var sourceRevisionToPublish: Long? = null
        mutex.lock()
        return try {
            if (expectedGeneration != null &&
                expectedGeneration != currentSourceMutationGenerationLocked()
            ) {
                false
            } else {
                sourceRevisionToPublish = advanceSourceIndexGeneration()
                mutation()
                presenceByRoot.remove(rootUri)
                true
            }
        } finally {
            mutex.unlock()
            sourceRevisionToPublish?.let { mutableSourceMutations.value = it }
        }
    }

    private fun currentGenerationLocked(): SourceIndexGeneration =
        SourceIndexGeneration("$processIncarnation:$indexGeneration", indexGeneration)

    private fun currentSourceMutationGenerationLocked(): SourceMutationGeneration =
        SourceMutationGeneration(processIncarnation, sourceMutationRevision)

    private fun advanceSourceIndexGeneration(): Long {
        check(sourceMutationRevision != Long.MAX_VALUE) {
            "Source mutation revision exhausted; refusing to lose a trailing identity pass"
        }
        advanceIndexGeneration()
        sourceMutationRevision += 1L
        return sourceMutationRevision
    }

    private fun advanceIndexGeneration() {
        check(indexGeneration != Long.MAX_VALUE) {
            "Source/index generation exhausted; refusing to reuse a stale publication witness"
        }
        indexGeneration += 1L
    }
}

/** One coordinator shared by the main-process repository and storage callback runtime. */
object ProcessSourceRootMutations {
    val coordinator = SourceRootMutationCoordinator()
}
