package com.gruning.mimir.rewrite

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A confirmed shutdown race: [PerformanceOverlayService]'s sampling loop runs on `Dispatchers.IO` and
 * calls [PerformanceOverlayPresenter.show], while `onDestroy` calls [PerformanceOverlayPresenter.destroy]
 * on the main thread. Off-main, `show()`'s only move is `main.post { show(next) }` — cancelling the
 * sampling coroutine does not remove an already-posted Runnable, and before this fix `show()` carried no
 * liveness check, so a `show` queued just before `destroy()` ran AFTER teardown and remounted a window
 * belonging to a dead presenter. Nothing was left alive to ever hide it again.
 *
 * [PerformanceOverlayPresenter.mountAttempts] is a test-only counter incremented at the top of
 * `ensureMounted()`, right after its `view != null` guard. It is the only reachable signal here: these
 * tests never reach a real `WindowManager.addView` result (Robolectric has no `TYPE_APPLICATION_OVERLAY`
 * window to show), so the counter — not a mounted view — is what proves whether a post-destroy `show`
 * tried to remount.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class PerformanceOverlayPostDestroyShowTest {
    @Test
    fun `a show queued before destroy never mounts after it`() {
        val presenter = PerformanceOverlayPresenter(RuntimeEnvironment.getApplication())
        val mainLooper = shadowOf(Looper.getMainLooper())
        mainLooper.idle()

        // Off-main, exactly like the sampling coroutine: show() takes the posting branch and queues a
        // Runnable on the main looper without mounting anything itself.
        val samplingThread = Thread { presenter.show(PerformanceOverlayPresentation.Warming) }
        samplingThread.start()
        samplingThread.join()

        // Teardown lands on the calling thread — in Robolectric that IS the main looper thread — before
        // the queued show is ever drained.
        presenter.destroy()
        mainLooper.idle()

        assertEquals(
            "a show queued before destroy must not mount once the presenter is dead; before the fix " +
                "the queued Runnable ran on idle and mounted a window nothing was left alive to hide",
            0,
            presenter.mountAttempts,
        )
    }

    @Test
    fun `a show after destroy is refused on the calling thread`() {
        val presenter = PerformanceOverlayPresenter(RuntimeEnvironment.getApplication())
        val mainLooper = shadowOf(Looper.getMainLooper())
        mainLooper.idle()

        presenter.destroy()
        // Directly on the test thread — in Robolectric that IS the main looper thread, so this takes the
        // same immediate (non-posting) branch of show() that a same-thread caller after teardown would.
        presenter.show(PerformanceOverlayPresentation.Warming)
        mainLooper.idle()

        assertEquals(
            "show() must refuse to mount once destroyed, even when called synchronously on the same " +
                "thread that ran destroy()",
            0,
            presenter.mountAttempts,
        )
    }
}
