package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
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

/**
 *  A 5,529-game library never stalls, so the stall
 * bound alone held the favourites Tome locked for minutes. The wait now also ends on the clock, and the Tome opens
 * to the player on what is identified so far.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingFavoritesWaitCapTest {
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

    @Test
    fun `a wait that outlasts the cap releases the Tome at 30 s, not before`() = runTest {
        val outcome = awaitFavoritesIdentificationCapped { awaitCancellation() }
        assertNull(outcome)
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun `a library that settles inside the cap keeps its settle, so the walk-6 auto-open still happens`() = runTest {
        val outcome = awaitFavoritesIdentificationCapped {
            delay(4_000)
            "settled"
        }
        assertEquals("settled", outcome)
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun `the cap is about thirty seconds and the page says identification carries on`() {
        assertEquals(30_000L, OnboardingFavoritesWaitCapMillis)
        val line = onboardingFavoritesBackgroundLine(res)
        assertTrue(line.contains("in the background"))
        assertFalse("the capped page must not promise the Tome opens when it finishes", line.contains("opens"))
    }

    @Test
    fun `the cap never opens the Tome by itself`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
        ).first(File::isFile).readText()
        val capped = source.substringAfter("if (outcome == null) {").substringBefore("return@produceState")
        assertTrue(capped.contains("favoritesTomeOpensOnSettle = false"))
        assertTrue(capped.contains("value = true"))
    }
}
