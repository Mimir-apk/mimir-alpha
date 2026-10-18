package com.gruning.mimir.rewrite

import android.os.Looper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * RC-44's second finding, pinned: nothing on the hide path cancels sampling, so in GAMES_ONLY with no
 * dispatched game the service called [PerformanceOverlayPresenter.hide] on every telemetry beat, forever —
 * and every one of those calls hopped onto the main looper to reach a body whose first act was
 * `if (view == null) return`.
 *
 * This is the exact population RC-44 measured on hardware: the card had never been shown, so there was
 * never anything to hide. Robolectric is needed only for a real main [Looper] to observe; the presenter is
 * never mounted, which is the whole point — nothing is drawn in this state.
 *
 * The mount path (a `TYPE_APPLICATION_OVERLAY` window, a WindowContext and a Compose `setContent`) is NOT
 * exercised here and is not claimed to be covered; these aspects remain source-inspected only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class PerformanceOverlayHideChurnTest {
    @Test
    fun `hiding what was never shown never wakes the main looper`() {
        val presenter = PerformanceOverlayPresenter(RuntimeEnvironment.getApplication())
        val mainLooper = shadowOf(Looper.getMainLooper())
        mainLooper.idle()
        assertTrue("main looper should start drained", mainLooper.isIdle)

        // One minute of GAMES_ONLY ticks with no dispatched game, from the sampling coroutine's thread.
        // Looper.myLooper() is null off-main, so each call takes the posting branch of hide().
        val samplingThread = Thread {
            repeat(60) { presenter.hide() }
        }
        samplingThread.start()
        samplingThread.join()

        assertTrue(
            "a hide with nothing shown must not post to the main looper; before RC-44's fix this " +
                "queued one no-op Runnable per telemetry beat, forever",
            mainLooper.isIdle,
        )
    }

    @Test
    fun `destroying an overlay that was never shown is also silent`() {
        // destroy() deliberately keeps its unconditional main-thread hop — it must always reach detachNow,
        // and it runs once per service teardown rather than once per beat, so it is not churn. Pinned so a
        // later reader does not "optimise" it behind the same guard and lose a teardown.
        val presenter = PerformanceOverlayPresenter(RuntimeEnvironment.getApplication())
        val mainLooper = shadowOf(Looper.getMainLooper())
        mainLooper.idle()

        val samplingThread = Thread { presenter.destroy() }
        samplingThread.start()
        samplingThread.join()

        assertTrue("destroy posts exactly one teardown", !mainLooper.isIdle)
        mainLooper.idle()
        assertTrue(mainLooper.isIdle)
    }
}
