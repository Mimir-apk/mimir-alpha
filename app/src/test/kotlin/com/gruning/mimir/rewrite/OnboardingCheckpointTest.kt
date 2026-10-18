package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCheckpointTest {

    @Test
    fun beginScanResetsPriorProgressAndRecordsTheNewScanGrant() {
        val prior = OnboardingCheckpoint(
            step = OnboardingStep.DONE,
            selectedTreeUri = "content://old-tree",
            scanGeneration = "old-generation",
            scanStatus = OnboardingScanStatus.FAILED,
            scanFilesSeen = 31,
            scanRecognized = 19,
            scanGroups = 7,
            scanSkipped = 12,
            scanFailure = "old scan failure",
            writeAccessGranted = false,
            settlementStatus = OnboardingSettlementStatus.LIMITED,
            settlementReason = "old settlement failure",
        )

        val checkpoint = prior.beginScan(
            treeUri = "content://new-tree",
            generation = "new-generation",
            writeAccessGranted = true,
        )

        assertEquals("content://new-tree", checkpoint.selectedTreeUri)
        assertEquals("new-generation", checkpoint.scanGeneration)
        assertEquals(OnboardingScanStatus.QUEUED, checkpoint.scanStatus)
        assertEquals(0, checkpoint.scanFilesSeen)
        assertEquals(0, checkpoint.scanRecognized)
        assertEquals(0, checkpoint.scanGroups)
        assertEquals(0, checkpoint.scanSkipped)
        assertNull(checkpoint.scanFailure)
        assertTrue(checkpoint.writeAccessGranted)
        assertEquals(OnboardingSettlementStatus.PENDING, checkpoint.settlementStatus)
        assertNull(checkpoint.settlementReason)
    }

    @Test
    fun updateScanRejectsAStaleGenerationAndLeavesTheLatestStateIntact() {
        val latest = requireNotNull(
            OnboardingCheckpoint().beginScan(
                treeUri = "content://latest-tree",
                generation = "latest-generation",
                writeAccessGranted = true,
            ).updateScan(
                generation = "latest-generation",
                status = OnboardingScanStatus.COMPLETE,
                filesSeen = 42,
                recognized = 29,
                groups = 8,
                skipped = 13,
            ),
        )

        val staleUpdate = latest.updateScan(
            generation = "replaced-generation",
            status = OnboardingScanStatus.FAILED,
            filesSeen = 1,
            recognized = 0,
            groups = 0,
            skipped = 1,
            failure = "stale worker failure",
        )

        assertNull(staleUpdate)
        assertEquals("content://latest-tree", latest.selectedTreeUri)
        assertEquals("latest-generation", latest.scanGeneration)
        assertEquals(OnboardingScanStatus.COMPLETE, latest.scanStatus)
        assertEquals(42, latest.scanFilesSeen)
        assertEquals(29, latest.scanRecognized)
        assertEquals(8, latest.scanGroups)
        assertEquals(13, latest.scanSkipped)
        assertNull(latest.scanFailure)
        assertTrue(latest.writeAccessGranted)
        assertEquals(OnboardingSettlementStatus.PENDING, latest.settlementStatus)
        assertNull(latest.settlementReason)
    }
}
