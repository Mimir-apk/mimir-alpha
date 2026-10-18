package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Overlay resume regression reproduced on device 2026-08-12.
 *
 * **This is the second version of this test file.** The first version pinned a `performanceOverlayResumeAction`
 * that took a `previouslyRunning: Boolean?` belief and only acted on a detected change from it -- and every
 * one of its tests passed while `tools/cert-overlay-permission-lifecycle.sh` FAILED assertion 2
 * (`service restarts on resume after the grant returns`) on real hardware. The unit tests were not wrong
 * about the function they tested; the function was wrong. [PerformanceOverlayService] stops **itself**
 * (`onStartCommand`/its telemetry loop call `stopSelf()` the instant `canDrawOverlays` goes false) without
 * `MainActivity` ever being told, so a "previously running" belief tracked by `MainActivity` alone silently
 * disagreed with reality the moment that happened -- a later resume with the permission restored saw
 * "no change from what I remember" and started nothing.
 *
 * [performanceOverlayResumeAction] now takes no memory of its own -- it is a pure function of the two live
 * inputs only, [PerformanceOverlayVisibility] and `canDrawOverlays`, and always resolves to
 * [PerformanceOverlayResumeAction.START] or [PerformanceOverlayResumeAction.STOP], never a "no-op" verdict
 * that could be stale. The differential: reverting the function to reintroduce a `previouslyRunning`
 * parameter and only act on a detected change (the exact shape of the falsified first fix) reproduces the
 * device failure directly -- "tracked=true, permission present, but the service actually died on its own"
 * (the fourth test below) is precisely the state the device was in when the harness failed.
 */
class PerformanceOverlayResumeActionTest {

    @Test
    fun `permission regained while backgrounded, preference ON -- service starts on resume`() {
        val action = performanceOverlayResumeAction(
            visibility = PerformanceOverlayVisibility.ALWAYS,
            canDrawOverlays = true,
        )

        assertEquals(PerformanceOverlayResumeAction.START, action)
    }

    @Test
    fun `permission lost while backgrounded -- service stops on resume`() {
        val action = performanceOverlayResumeAction(
            visibility = PerformanceOverlayVisibility.GAMES_ONLY,
            canDrawOverlays = false,
        )

        assertEquals(PerformanceOverlayResumeAction.STOP, action)
    }

    @Test
    fun `preference OFF, permission regained -- service is NOT started`() {
        val action = performanceOverlayResumeAction(
            visibility = PerformanceOverlayVisibility.OFF,
            canDrawOverlays = true,
        )

        assertEquals(PerformanceOverlayResumeAction.STOP, action)
    }

    /**
     * The exact scenario `cert-overlay-permission-lifecycle.sh` caught on the device: the permission was
     * revoked, [PerformanceOverlayService] stopped itself in response (a fact this function has no way to
     * observe and must not need to), and the permission has now been restored. Whatever any caller might
     * believe about whether the service is currently running, the decision must still be START -- there is
     * no "previously running" input left to get this wrong from.
     */
    @Test
    fun `permission present and preference enabled -- always resolves START regardless of any prior belief`() {
        val afterSelfStopThenRegrant = performanceOverlayResumeAction(
            visibility = PerformanceOverlayVisibility.ALWAYS,
            canDrawOverlays = true,
        )
        assertEquals(PerformanceOverlayResumeAction.START, afterSelfStopThenRegrant)

        // Repeating the identical call must repeat the identical answer -- deterministic, not a diffed
        // reported failure to count the repeated activation.
        val repeated = performanceOverlayResumeAction(
            visibility = PerformanceOverlayVisibility.ALWAYS,
            canDrawOverlays = true,
        )
        assertEquals(PerformanceOverlayResumeAction.START, repeated)
    }

    @Test
    fun `repeated resumes with no state change -- always resolve the same live verdict`() {
        // Steady state while the permission holds and the preference is enabled: every resume resolves
        // START. Redundant start() calls are cheap/no-op re-deliveries -- see
        // MainActivity.applyPerformanceOverlayLiveDecision's doc comment for why this is not "thrash".
        repeat(3) {
            assertEquals(
                PerformanceOverlayResumeAction.START,
                performanceOverlayResumeAction(
                    visibility = PerformanceOverlayVisibility.ALWAYS,
                    canDrawOverlays = true,
                ),
            )
        }

        // Steady state while stopped (OFF preference): every resume resolves STOP, never START.
        repeat(3) {
            assertEquals(
                PerformanceOverlayResumeAction.STOP,
                performanceOverlayResumeAction(
                    visibility = PerformanceOverlayVisibility.OFF,
                    canDrawOverlays = true,
                ),
            )
        }

        // Steady state while stopped (permission missing): every resume resolves STOP, never START.
        repeat(3) {
            assertEquals(
                PerformanceOverlayResumeAction.STOP,
                performanceOverlayResumeAction(
                    visibility = PerformanceOverlayVisibility.ALWAYS,
                    canDrawOverlays = false,
                ),
            )
        }
    }
}
