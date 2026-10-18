package com.gruning.mimir.rewrite

import android.os.Handler
import android.os.Looper
import android.view.View

/*
 * O11b — splash-first composition: WHEN the app
 * content may mount under the Welcome splash on a returning user's cold start. [MimirColdStartWelcome] holds the splash;
 * this file decides the moment. Line numbers below are android13-release sources (the Thor runs Android 13).
 *
 * ## The report has to go first
 * `Displayed` is logged by system_server once this window's first draw is reported (ActivityMetricsLogger
 * `notifyWindowsDrawn` / `logAppDisplayed`). On Android 13 that report leaves the app in a MAIN-THREAD message. The
 * traversal arms a sync for a draw it must report (`reportNextDraw`, ViewRootImpl.java:3487-3488 for a first draw and
 * 2981-2985 for a BLAST sync; `createSyncIfNeeded`, 3540-3557). The sync's completion posts `reportDrawFinished`, the
 * oneway `IWindowSession.finishDrawing` (ViewRootImpl.java:4178, IWindowSession.aidl:181), with `postAtFrontOfQueue`
 * (ViewRootImpl.java:3549). The front of the queue is not preemption: if the content's first composition (about 1 s on
 * the Thor, O11) were already running in a Choreographer frame, the report would wait behind it and `Displayed` would
 * not move.
 *
 * ## When the report is queued
 * `performDraw` hands this window's frame-commit callbacks to the renderer BEFORE the sync's frame callback
 * (ViewRootImpl.java:4276, then 4280). On the RenderThread, `DrawFrameTask::run` adds the commit callback as a listener
 * first (DrawFrameTask.cpp:156-158) and the sync's listener later, from frame work (180-186) that `draw` joins before
 * the swap (CanvasContext.cpp:521). `CanvasContext::draw` then runs the listeners in that order after `swapBuffers`
 * (537, 614-619), or with `false` for a skipped frame (495-500). Each commit-callback message is posted with
 * `postAtFrontOfQueue` (ViewRootImpl.java:4231).
 *  - With a buffer sync, the report is posted inside the swap: `BLASTBufferQueue::onFrameAvailable` runs the sync
 *    callback inline (BLASTBufferQueue.cpp:686-699), which completes through SurfaceSyncer.java:332-339 and 364-383.
 *    Or `markSyncReady` posts it at the end of the traversal (ViewRootImpl.java:3533-3534). Either way it is queued
 *    before the splash's first commit message can run.
 *  - Without one, the sync's own commit listener reports (ViewRootImpl.java:10930-10932), and it runs AFTER ours in the
 *    same loop. Mounting from the first commit alone could, in principle, overtake it.
 *
 * ## The handshake
 * 1. Armed in the splash's first composition, before that traversal draws: register a frame-commit callback.
 * 2. The splash's first frame commits: register another, ask for one more frame, do not mount.
 * 3. The splash's second frame commits. The RenderThread draws frames one after another, so frame 1's whole listener
 *    loop, and frame 1's report with it, was queued before this message existed. Post the mount to the BACK of the main
 *    queue (`Handler.post`), behind that report.
 * 4. The post runs and mounts. The state write composes the content in a later Choreographer frame.
 * Net main-thread order: report, mount, content composition. A JVM test's frame clock is not the device's draw report,
 * so the tests pin only the sequence of callbacks and queue positions (SplashFirstDrawHandshakeTest,
 * SplashFirstDrawPortsTest). On the Thor the order is device-owed: read the MimirColdStart lines against `Displayed`.
 *
 * ## Bounds
 * [SplashFirstMountDeadlineMs] mounts the content anyway when the handshake has not finished (a window hidden right after
 * it composes commits no frame). A software-rendered window gets no handshake ([splashContentMountGateFor] returns
 * null) and keeps the content in the first frame.
 */

/**
 * The longest the content waits for the handshake, from arming (the splash's first composition). NOT measured: a safety
 * bound for a window that composes but does not draw. On a visible cold start the handshake is expected to finish first,
 * and the `content mount requested reason=` line says which did. It stays well inside the 3.2 s beat
 * (SplashFirstDrawHandshakeTest), so the splash cannot dissolve onto content that never mounted.
 */
internal const val SplashFirstMountDeadlineMs = 1_000L

/** What mounted the content under the splash. [logName] is the `reason=` in the cold-start timeline. */
internal enum class SplashFirstMountReason(val logName: String) {
    HANDSHAKE("handshake"),
    DEADLINE("deadline"),
}

/** The seam [MimirColdStartWelcome] waits on. [arm] calls `onMount` at most once, on the main thread, and returns the cancel. */
internal fun interface SplashContentMountGate {
    fun arm(onMount: (SplashFirstMountReason) -> Unit): () -> Unit
}

/** The platform calls the handshake makes, all on the main thread. Each registration returns its own withdrawal. */
internal interface SplashFirstDrawPorts {
    /** Runs [callback] on the main thread once the next frame this window draws has been committed. */
    fun onNextFrameCommit(callback: Runnable): () -> Unit

    /** Asks the window for one more frame. */
    fun requestFrame()

    /** Posts [task] to the BACK of the main queue. Never the front: the draw report has to stay ahead of it. */
    fun postToBackOfQueue(task: Runnable): () -> Unit

    fun postDelayed(delayMs: Long, task: Runnable): () -> Unit
}

/** One arming of the handshake described at the top of this file. Main thread only; single use. */
internal class SplashFirstDrawHandshake(
    private val ports: SplashFirstDrawPorts,
    private val onMount: (SplashFirstMountReason) -> Unit,
    private val deadlineMs: Long = SplashFirstMountDeadlineMs,
    private val trace: (event: String, detail: String) -> Unit = MimirColdStartTrace::mark,
) {
    private var started = false
    private var finished = false
    private var withdrawCommit: (() -> Unit)? = null
    private var withdrawPost: (() -> Unit)? = null
    private var withdrawDeadline: (() -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        trace("handshake armed", "deadlineMs=$deadlineMs")
        withdrawDeadline = ports.postDelayed(deadlineMs) { mount(SplashFirstMountReason.DEADLINE) }
        withdrawCommit = ports.onNextFrameCommit { onSplashFrameCommitted(frame = 1) }
    }

    fun cancel() {
        if (finished) return
        finished = true
        withdrawAll()
    }

    private fun onSplashFrameCommitted(frame: Int) {
        if (finished) return
        trace("handshake splash frame $frame committed", "")
        if (frame == 1) {
            // Not yet: without a buffer sync, this frame's report is queued after this very callback.
            withdrawCommit = ports.onNextFrameCommit { onSplashFrameCommitted(frame = 2) }
            ports.requestFrame()
        } else {
            withdrawCommit = null
            withdrawPost = ports.postToBackOfQueue { mount(SplashFirstMountReason.HANDSHAKE) }
        }
    }

    private fun mount(reason: SplashFirstMountReason) {
        if (finished) return
        finished = true
        withdrawAll()
        onMount(reason)
    }

    private fun withdrawAll() {
        withdrawDeadline?.invoke()
        withdrawCommit?.invoke()
        withdrawPost?.invoke()
        withdrawDeadline = null
        withdrawCommit = null
        withdrawPost = null
    }
}

/** [SplashFirstDrawPorts] over a real window: its tree observer's frame-commit callbacks, `invalidate`, the main Handler. */
internal class ViewSplashFirstDrawPorts(private val view: View) : SplashFirstDrawPorts {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onNextFrameCommit(callback: Runnable): () -> Unit {
        // Captured by the window's next hardware draw (ViewRootImpl.java:4214-4215).
        view.viewTreeObserver.registerFrameCommitCallback(callback)
        return {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
        }
    }

    override fun requestFrame() {
        view.invalidate()
    }

    override fun postToBackOfQueue(task: Runnable): () -> Unit {
        mainHandler.post(task)
        return { mainHandler.removeCallbacks(task) }
    }

    override fun postDelayed(delayMs: Long, task: Runnable): () -> Unit {
        mainHandler.postDelayed(task, delayMs)
        return { mainHandler.removeCallbacks(task) }
    }
}

/**
 * The production gate for [view]'s window, or `null` when it renders in software: the platform runs no frame-commit
 * callbacks there, so there is no handshake to wait on and the content keeps mounting with the first frame.
 */
internal fun splashContentMountGateFor(view: View): SplashContentMountGate? {
    if (!view.isHardwareAccelerated) return null
    return SplashContentMountGate { onMount ->
        val handshake = SplashFirstDrawHandshake(ViewSplashFirstDrawPorts(view), onMount)
        handshake.start()
        handshake::cancel
    }
}
