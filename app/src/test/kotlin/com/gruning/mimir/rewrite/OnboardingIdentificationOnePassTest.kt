package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Identification of a 5529-game RG Rotate library must continue across worker slices and open the Tome after settling.
 *
 * What the log showed: the first 7-minute slice attempted 3150 of 5529 and exited to a retry at 21:16:10; the
 * second started at 21:16:40 counting "0 of 1576". The favourites wait had no bound, and the intro's "Your Tome
 * opens when it finishes" only ever unlocked a button.
 */
class OnboardingIdentificationOnePassTest {

    @Before
    @After
    fun resetSingletonState() {
        IdentityEnrichmentLiveProgress.clear()
    }

    // ── One count across slices ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a continuation counts on from the slice before it, with walk 6's own numbers`() {
        val first = IdentityEnrichmentLiveProgress.start(5_529)
        IdentityEnrichmentLiveProgress.publish(first, 3_150)
        IdentityEnrichmentLiveProgress.clear(first, continuesSweep = true)

        val second = IdentityEnrichmentLiveProgress.start(1_576, continuesSweep = true)
        val resumed = IdentityEnrichmentLiveProgress.progress.value!!
        assertEquals("the bar resumes where the first slice stopped", 3_150, resumed.attempted)
        // 5529 - 3150 is not 1576: rows resolve without being attempted. Recomputing the total keeps the end
        // reachable; the fraction steps UP (57% -> 67%), never back to zero.
        assertEquals(3_150 + 1_576, resumed.total)
        assertTrue(3_150f / 4_726 > 3_150f / 5_529)

        IdentityEnrichmentLiveProgress.publish(second, 1_576)
        val done = IdentityEnrichmentLiveProgress.progress.value!!
        assertEquals("it ends at its own total", done.total, done.attempted)
        assertEquals(0, IdentityEnrichmentLiveProgress.remaining.value)
    }

    @Test
    fun `a fresh sweep starts from zero even after a sliced one`() {
        val first = IdentityEnrichmentLiveProgress.start(100)
        IdentityEnrichmentLiveProgress.publish(first, 60)
        IdentityEnrichmentLiveProgress.clear(first, continuesSweep = true)

        IdentityEnrichmentLiveProgress.start(40)
        val fresh = IdentityEnrichmentLiveProgress.progress.value!!
        assertEquals(0, fresh.attempted)
        assertEquals(40, fresh.total)
    }

    @Test
    fun `a finished or failed run ends the sweep's count`() {
        val first = IdentityEnrichmentLiveProgress.start(100)
        IdentityEnrichmentLiveProgress.publish(first, 60)
        IdentityEnrichmentLiveProgress.clear(first)

        // Even a run that claims to continue has nothing to continue from.
        IdentityEnrichmentLiveProgress.start(40, continuesSweep = true)
        assertEquals(0, IdentityEnrichmentLiveProgress.progress.value!!.attempted)
    }

    @Test
    fun `a stale exit cannot rewrite the carry of the run that replaced it`() {
        val stale = IdentityEnrichmentLiveProgress.start(100)
        IdentityEnrichmentLiveProgress.publish(stale, 60)
        IdentityEnrichmentLiveProgress.clear(stale, continuesSweep = true)
        val live = IdentityEnrichmentLiveProgress.start(40, continuesSweep = true)

        IdentityEnrichmentLiveProgress.clear(stale) // late, from the old run
        IdentityEnrichmentLiveProgress.publish(live, 10)
        assertEquals(70, IdentityEnrichmentLiveProgress.progress.value!!.attempted)
    }

    @Test
    fun `slices are separated by WorkManager's floor, not the 30 second default`() {
        val source = workerSource()
        assertTrue(
            Regex("""setBackoffCriteria\(\s*BackoffPolicy\.LINEAR,\s*WorkRequest\.MIN_BACKOFF_MILLIS,""")
                .containsMatchIn(source),
        )
    }

    // ── The wait is bounded by progress ──────────────────────────────────────────────────────────────

    @Test
    fun `the wait returns as soon as identification settles, and counts what it identified`() = runTest {
        var outstanding = 1_576
        val settle = CompletableDeferred<Unit>()
        val result = async {
            awaitSettledUnlessStalled(
                awaitSettled = { settle.await() },
                outstanding = { outstanding },
                nowMillis = { testScheduler.currentTime },
            )
        }
        advanceTimeBy(60_000L)
        outstanding = 0
        settle.complete(Unit)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(IdentitySettleOutcome(settled = true, identified = 1_576), result.await())
    }

    @Test
    fun `rows that cannot resolve settle with nothing identified`() = runTest {
        // Emulator 2026-09-27: six files that never resolve kept the favourites wait up for 1.7 s -- two re-try
        // passes -- on a library that was already identified.
        val settle = CompletableDeferred<Unit>()
        val result = async {
            awaitSettledUnlessStalled(
                awaitSettled = { settle.await() },
                outstanding = { 6 },
                nowMillis = { testScheduler.currentTime },
            )
        }
        advanceTimeBy(1_700L)
        settle.complete(Unit)
        runCurrent()
        assertEquals(IdentitySettleOutcome(settled = true, identified = 0), result.await())
    }

    @Test
    fun `a long sweep that keeps converging is waited out, however long`() = runTest {
        // Twenty minutes, one 250-game chunk every 30 seconds: slow, never stuck.
        var outstanding = 5_529
        val settle = CompletableDeferred<Unit>()
        val result = async {
            awaitSettledUnlessStalled(
                awaitSettled = { settle.await() },
                outstanding = { outstanding },
                nowMillis = { testScheduler.currentTime },
            )
        }
        repeat(40) {
            advanceTimeBy(30_000L)
            outstanding = (outstanding - 130).coerceAtLeast(1)
        }
        runCurrent()
        assertFalse("still converging, so still waiting", result.isCompleted)
        settle.complete(Unit)
        runCurrent()
        assertTrue(result.await().settled)
    }

    @Test
    fun `a sweep whose count stops moving is abandoned after the stall budget`() = runTest {
        var outstanding = 5_529
        val result = async {
            awaitSettledUnlessStalled(
                awaitSettled = { CompletableDeferred<Unit>().await() },
                outstanding = { outstanding },
                nowMillis = { testScheduler.currentTime },
            )
        }
        runCurrent()
        outstanding = 1_576 // one slice lands, then nothing more
        advanceTimeBy(IdentityStallPollMillis)
        runCurrent()
        advanceTimeBy(IdentityStallBudgetMillis - IdentityStallPollMillis)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2 * IdentityStallPollMillis)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(IdentitySettleOutcome(settled = false, identified = 5_529 - 1_576), result.await())
    }

    @Test
    fun `the favourites step waits through the bounded call, never the bare one`() {
        val coordinator = File(sourceRoot(), "MimirOnboardingCoordinator.kt").readText()
        val gate = coordinator.substringAfter("val favoritesIdentitySettled").substringBefore("val favoritesLiveProgress")
        assertTrue(gate.contains("awaitSettledUnlessStalled(context)"))
        assertFalse(Regex("""awaitSettled\(context\)""").containsMatchIn(gate))
    }

    // ── The Tome opens when it finishes ──────────────────────────────────────────────────────────────

    @Test
    fun `the Tome opens when a wait that identified games ends on the intro`() {
        assertTrue(
            onboardingFavoritesAutoOpensTome(
                wasIdentifying = true,
                identifyingNow = false,
                identifiedWhileWaiting = true,
                page = OnboardingFavoritesPage.INTRO,
            ),
        )
    }

    @Test
    fun `the Tome never opens by itself otherwise`() {
        val page = OnboardingFavoritesPage.INTRO
        // Still identifying.
        assertFalse(onboardingFavoritesAutoOpensTome(true, identifyingNow = true, identifiedWhileWaiting = true, page = page))
        // Already identified: the wait only re-tried rows that cannot resolve, so the intro keeps its turn.
        assertFalse(onboardingFavoritesAutoOpensTome(true, identifyingNow = false, identifiedWhileWaiting = false, page = page))
        // No wait ended here (the player closed the Tome and came back to the intro).
        assertFalse(onboardingFavoritesAutoOpensTome(false, identifyingNow = false, identifiedWhileWaiting = true, page = page))
        // Already in the Tome.
        assertFalse(
            onboardingFavoritesAutoOpensTome(true, false, identifiedWhileWaiting = true, page = OnboardingFavoritesPage.TOME),
        )
    }

    private fun sourceRoot(): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite"),
    ).first(File::isDirectory)

    private fun workerSource(): String = File(sourceRoot(), "IdentityEnrichmentWorker.kt").readText()
}
