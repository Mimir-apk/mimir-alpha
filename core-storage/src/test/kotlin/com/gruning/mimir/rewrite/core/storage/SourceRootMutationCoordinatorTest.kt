package com.gruning.mimir.rewrite.core.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRootMutationCoordinatorTest {

    @Test
    fun `scan publication invalidates an older background index snapshot`() = runBlocking {
        val coordinator = SourceRootMutationCoordinator()
        val snapshot = coordinator.snapshotIndex { "old resident" }

        assertTrue(coordinator.commitScanIfPresent("root") {})

        var staleMutationRan = false
        assertFalse(
            coordinator.commitIndexIfUnchanged(snapshot.generation) {
                staleMutationRan = true
            },
        )
        assertFalse(staleMutationRan)
    }

    @Test
    fun `successful guarded publication invalidates another holder of the same witness`() = runBlocking {
        val coordinator = SourceRootMutationCoordinator()
        val first = coordinator.snapshotIndex { 1 }
        val second = coordinator.snapshotIndex { 2 }
        var publications = 0

        assertTrue(
            coordinator.commitIndexIfUnchanged(first.generation) {
                publications += first.value
            },
        )
        assertFalse(
            coordinator.commitIndexIfUnchanged(second.generation) {
                publications += second.value
            },
        )
        assertEquals(1, publications)
    }

    @Test
    fun `new volume truth invalidates resident work even when its room mutation fails`() = runBlocking {
        val coordinator = SourceRootMutationCoordinator()
        val snapshot = coordinator.snapshotIndex { Unit }

        val failedMutation = runCatching {
            coordinator.applyPresenceSnapshot(
                absentRootUris = listOf("root"),
                presentRootUris = emptyList(),
                mutation = { error("forced Room failure") },
            )
        }

        assertTrue(failedMutation.isFailure)
        assertFalse(coordinator.commitIndexIfUnchanged(snapshot.generation) {})
    }

    @Test
    fun `source mutation publishes a trailing identity signal after commit`() = runBlocking {
        val coordinator = SourceRootMutationCoordinator()

        assertTrue(coordinator.commitScanIfPresent("root") {})

        // StateFlow is intentionally replayed: startup volume/scan truth may land before the app
        // collector subscribes, and one trailing pass must still be observed.
        assertEquals(1L, withTimeout(1_000) { coordinator.sourceMutations.first { it > 0L } })
    }

    @Test
    fun `source witness rejects stale scan and removal publication`() = runBlocking {
        val coordinator = SourceRootMutationCoordinator()
        val stale = coordinator.currentSourceMutationGeneration()
        coordinator.applyPresenceSnapshot(
            absentRootUris = emptyList(),
            presentRootUris = listOf("root"),
            mutation = {},
        )

        var scanPublished = false
        var removalPublished = false
        assertEquals(
            SourceScanCommitResult.STALE,
            coordinator.commitScanIfCurrent("root", stale) {
                scanPublished = true
            },
        )
        assertFalse(
            coordinator.commitRootRemoval("root", stale) {
                removalPublished = true
            },
        )
        assertFalse(scanPublished)
        assertFalse(removalPublished)
    }

    @Test
    fun `identity-only index publication does not invalidate source witness`() = runBlocking {
        val coordinator = SourceRootMutationCoordinator()
        val sourceWitness = coordinator.currentSourceMutationGeneration()
        val indexSnapshot = coordinator.snapshotIndex { Unit }

        assertTrue(coordinator.commitIndexIfUnchanged(indexSnapshot.generation) {})
        assertTrue(coordinator.commitIfSourceUnchanged(sourceWitness) {})
    }
}
