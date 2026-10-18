package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingIdentityProgressTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val res = onboardingTestResources()

    private fun checkpoint() = OnboardingCheckpoint(scanStatus = OnboardingScanStatus.COMPLETE)

    @Test
    fun latestActiveGroupingWorkerIsProjectedWithoutInventingADenominator() {
        val progress = identityEnrichmentProgressFor(
            listOf(
                IdentityEnrichmentWorkRecord(false, "IDENTIFYING", 61, 61, 0),
                IdentityEnrichmentWorkRecord(true, "GROUPING", 61, 61, 0),
            ),
        )
        assertEquals(IdentityEnrichmentStage.GROUPING, progress?.stage)
        val ui = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 0,
            identityFinalizing = true,
            identityProgress = progress,
        )
        assertEquals("Grouping related releases", ui.title)
        assertNull(ui.total)
    }

    @Test
    fun mismatchedVisibleAndUnresolvedPopulationsUseLiveCheckedSnapshot() {
        // A hypothetical 4,686 visible games can be fewer than Room's 5,529 unresolved primary rows.
        // The old subtraction clamped to zero; the same-run attempted/total pair remains truthful.
        val live = IdentityEnrichmentLiveSnapshot(runId = 1L, attempted = 1_200, total = 5_529)
        val fraction = onboardingIdentifyingFraction(live)
        assertEquals(5_529, fraction.total)
        assertEquals(1_200, fraction.done)

        val ui = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 5_529,
            identityFinalizing = false,
            liveIdentityProgress = live,
        )
        assertEquals(5_529, ui.total)
        assertEquals(1_200, ui.done)
        assertEquals("No artwork is downloaded.", ui.detail)
    }

    @Test
    fun firstCompletedHashShowsBeforeItsBatchCommits() {
        // Room still reports the original unresolved population; the callback already published one decision.
        val ui = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 3,
            identityFinalizing = false,
            liveIdentityProgress = IdentityEnrichmentLiveSnapshot(runId = 2L, attempted = 1, total = 3),
        )
        assertEquals(1, ui.done)
        assertEquals(3, ui.total)
    }

    @Test
    fun completedHashingStaysIndeterminateWhileCommitAndRegroupRemain() {
        val ui = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 1,
            identityFinalizing = false,
            liveIdentityProgress = IdentityEnrichmentLiveSnapshot(runId = 3L, attempted = 3, total = 3),
        )
        assertEquals(OnboardingSettlementPhase.IDENTIFYING, ui.phase)
        assertNull(ui.total)
        assertEquals("Finishing up. No artwork is downloaded.", ui.detail)
    }

    @Test
    fun groupingStaysIndeterminateEvenWithAnIdentifyingSnapshot() {
        val ui = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 0,
            identityFinalizing = true,
            identityProgress = IdentityEnrichmentProgress(
                IdentityEnrichmentStage.GROUPING, attempted = 3, matched = 3, remaining = 0,
            ),
            liveIdentityProgress = IdentityEnrichmentLiveSnapshot(runId = 4L, attempted = 3, total = 3),
        )
        assertEquals("Grouping related releases", ui.title)
        assertNull(ui.total)
    }

    @Test
    fun noSnapshotDoesNotMixRoomCountIntoAProgressFraction() {
        val ui = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 5,
            identityFinalizing = false,
        )
        assertNull(ui.total)
        assertEquals("5 games still to check.", ui.detail)
    }

    @Test
    fun identifyingNeverHoldsTheUserAndASettledLibraryShowsNoSecondBar() {
        // hashing progress must not block UI progress. Identification
        // is one step, on the favourites intro, so a settled library does not show the run a second time.
        val live = IdentityEnrichmentLiveSnapshot(runId = 7L, attempted = 40, total = 5_529)
        val identifying = onboardingSettlementUi(
            res,
            checkpoint = checkpoint(),
            resolvingCount = 5_529,
            identityFinalizing = true,
            liveIdentityProgress = live,
        )
        assertEquals(OnboardingSettlementPhase.IDENTIFYING, identifying.phase)
        assertTrue(identifying.canContinue)

        val limited = onboardingSettlementUi(
            res,
            checkpoint = checkpoint().copy(settlementStatus = OnboardingSettlementStatus.LIMITED),
            resolvingCount = 5_489,
            identityFinalizing = false,
            liveIdentityProgress = live,
        )
        assertEquals(OnboardingSettlementPhase.LIMITED, limited.phase)
        assertTrue(limited.canContinue)
        assertNull(limited.total)

        val ready = onboardingSettlementUi(
            res,
            checkpoint = checkpoint().copy(settlementStatus = OnboardingSettlementStatus.READY),
            resolvingCount = 5_489,
            identityFinalizing = false,
            liveIdentityProgress = live,
        )
        assertEquals(OnboardingSettlementPhase.READY, ready.phase)
        assertNull(ready.total)

        val idle = onboardingSettlementUi(
            res,
            checkpoint = checkpoint().copy(settlementStatus = OnboardingSettlementStatus.READY),
            resolvingCount = 0,
            identityFinalizing = false,
        )
        assertEquals(null, idle.total)
        assertFalse(idle.canRetry)
    }

    @Test
    fun libraryBuildingCountsStateOnlyWorkThatHappened() {
        assertEquals(null, onboardingLibraryBuildingCounts(res, null))
        assertEquals(null, onboardingLibraryBuildingCounts(res, com.gruning.mimir.rewrite.core.model.jobs.ScanProgress(com.gruning.mimir.rewrite.core.model.jobs.ScanPhase.WALKING, 0)))
        assertEquals("Found 1 file", onboardingLibraryBuildingCounts(res, com.gruning.mimir.rewrite.core.model.jobs.ScanProgress(com.gruning.mimir.rewrite.core.model.jobs.ScanPhase.WALKING, 1)))
        assertEquals(
            "Recognizing 120 of 8972 files",
            onboardingLibraryBuildingCounts(res, com.gruning.mimir.rewrite.core.model.jobs.ScanProgress(com.gruning.mimir.rewrite.core.model.jobs.ScanPhase.RECOGNISING, 120, 8972)),
        )
    }

    @Test
    fun favouriteCandidatesFollowTheLibraryPlatformOrder() {
        fun row(id: String, platform: String) = com.gruning.mimir.rewrite.core.model.library.GameRow(
            gameId = com.gruning.mimir.rewrite.core.model.GameId(id),
            title = id,
            platformId = com.gruning.mimir.rewrite.core.model.PlatformId(platform),
            memberCount = 1,
            duplicateKind = null,
        )
        val rows = listOf(row("a", "gba"), row("b", "snes"), row("c", "gba"), row("d", "ps1"), row("e", "odd"))
        val ordered = onboardingFavoriteCandidatesInPlatformOrder(
            listOf("snes", "ps1", "gba").map { com.gruning.mimir.rewrite.core.model.PlatformId(it) },
            rows,
        )
        assertEquals(listOf("b", "d", "a", "c", "e"), ordered.map { it.gameId.value })
    }

    @Test
    fun favouritesIdentifyingLineStatesProgressOnlyWhenItHasADenominator() {
        assertEquals(
            "Identifying your games: 1200 of 5529. The Tome unlocks soon.",
            onboardingFavoritesIdentifyingLine(res, OnboardingFavoritesIdentifying(done = 1_200, total = 5_529)),
        )
        assertEquals(
            "Identifying your games. The Tome unlocks soon.",
            onboardingFavoritesIdentifyingLine(res, OnboardingFavoritesIdentifying(done = 0, total = null)),
        )
    }

    @Test
    fun favouriteCandidatesAlreadyInPlatformOrderComeBackAsTheSameList() {
        // RG Rotate observation: the picker sorted 5,500 rows on every read even when they arrived in order.
        fun row(id: String, platform: String) = com.gruning.mimir.rewrite.core.model.library.GameRow(
            gameId = com.gruning.mimir.rewrite.core.model.GameId(id),
            title = id,
            platformId = com.gruning.mimir.rewrite.core.model.PlatformId(platform),
            memberCount = 1,
            duplicateKind = null,
        )
        val rows = listOf(row("b", "snes"), row("d", "ps1"), row("a", "gba"), row("c", "gba"), row("e", "odd"))
        val ordered = onboardingFavoriteCandidatesInPlatformOrder(
            listOf("snes", "ps1", "gba").map { com.gruning.mimir.rewrite.core.model.PlatformId(it) },
            rows,
        )
        org.junit.Assert.assertSame(rows, ordered)
    }
}
