package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * the platform half of the splash-first handshake ([ViewSplashFirstDrawPorts], [splashContentMountGateFor]) on a
 * real attached View and the real main MessageQueue (Robolectric's paused looper).
 *
 * The queue tests are the ones that matter: Android 13 queues the draw report at the FRONT (ViewRootImpl.java:3549,
 * android13-release) and the mount must land behind it. Robolectric cannot draw on a RenderThread, so a frame commit is
 * driven by hand through the tree observer's own capture, the call `performDraw` makes (ViewRootImpl.java:4214-4215).
 * That the Thor orders the real messages the same way is device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SplashFirstDrawPortsTest {

    private class CountingView(context: Context) : View(context) {
        var invalidations = 0

        override fun invalidate() {
            invalidations++
            super.invalidate()
        }
    }

    private fun attachedView(): CountingView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = CountingView(activity)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    /** What the window's next hardware draw would take (ViewTreeObserver.captureFrameCommitCallbacks, hidden). */
    @Suppress("UNCHECKED_CAST")
    private fun captureFrameCommitCallbacks(view: View): List<Runnable> {
        val capture = ViewTreeObserver::class.java.getDeclaredMethod("captureFrameCommitCallbacks")
        capture.isAccessible = true
        return (capture.invoke(view.viewTreeObserver) as ArrayList<Runnable>?).orEmpty()
    }

    private fun commitFrame(view: View) = captureFrameCommitCallbacks(view).forEach(Runnable::run)

    private fun queueReportAtFront(order: MutableList<String>) {
        Handler(Looper.getMainLooper()).postAtFrontOfQueue { order += "report" }
    }

    @Test
    fun `the mount post lands behind a draw report already queued at the front`() {
        val ports = ViewSplashFirstDrawPorts(attachedView())
        val order = mutableListOf<String>()

        queueReportAtFront(order)
        ports.postToBackOfQueue { order += "mount" }

        assertEquals("posting must not run the mount inline", emptyList<String>(), order)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("report", "mount"), order)
    }

    @Test
    fun `end to end on the real queue - report first, then the mount, and no commit callback mounts`() {
        val view = attachedView()
        val order = mutableListOf<String>()
        val handshake = SplashFirstDrawHandshake(
            ports = ViewSplashFirstDrawPorts(view),
            onMount = { order += "mount:${it.logName}" },
            deadlineMs = 60_000L,
            trace = { _, _ -> },
        )
        handshake.start()

        commitFrame(view) // the splash's first frame
        assertEquals(emptyList<String>(), order)

        // Frame 1's RenderThread loop ends by queueing the report at the front (the no-buffer-sync case).
        queueReportAtFront(order)
        commitFrame(view) // the splash's second frame
        assertEquals("frame 2's commit must post the mount, not run it", emptyList<String>(), order)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("report", "mount:handshake"), order)
        handshake.cancel()
    }

    @Test
    fun `a frame-commit callback goes on the window's tree observer and its withdrawal takes it off`() {
        val view = attachedView()
        val ports = ViewSplashFirstDrawPorts(view)
        val withdrawn = Runnable { }
        val kept = Runnable { }

        ports.onNextFrameCommit(withdrawn)()
        ports.onNextFrameCommit(kept)

        assertEquals(listOf(kept), captureFrameCommitCallbacks(view))
    }

    @Test
    fun `asking for another frame invalidates the view`() {
        val view = attachedView()
        val before = view.invalidations

        ViewSplashFirstDrawPorts(view).requestFrame()

        assertEquals(before + 1, view.invalidations)
    }

    @Test
    fun `a software-rendered window gets no handshake, a hardware one does`() {
        val context = attachedView().context
        val software = object : View(context) {
            override fun isHardwareAccelerated() = false
        }
        val hardware = object : View(context) {
            override fun isHardwareAccelerated() = true
        }

        assertNull(splashContentMountGateFor(software))
        assertNotNull(splashContentMountGateFor(hardware))
        assertTrue(SplashFirstMountDeadlineMs > 0L)
    }
}
